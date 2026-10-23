package com.salesapp.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.DEFAULT_VAT_RATE
import com.salesapp.core.lineAmount
import com.salesapp.core.tomorrowIsoDate
import com.salesapp.core.vatOf
import com.salesapp.data.AppState
import com.salesapp.data.BookingCompany
import com.salesapp.data.BookingInfo
import com.salesapp.data.NewOrderLine
import com.salesapp.data.NewOrderRequest
import com.salesapp.data.OrdersRepository
import com.salesapp.data.Product
import com.salesapp.data.UserRow
import com.salesapp.data.canBookOrders
import com.salesapp.data.companyCodes
import com.salesapp.data.companyName
import com.salesapp.data.picksSalesman
import com.salesapp.resources.Res
import com.salesapp.resources.order_book_booked
import com.salesapp.resources.order_book_err_lines
import com.salesapp.resources.order_book_err_lpo
import com.salesapp.resources.order_book_err_price
import com.salesapp.resources.order_book_err_qty
import com.salesapp.resources.order_book_err_salesman
import com.salesapp.ui.stock.formProductTitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// One product on the order being booked. Blank boxes are null.
data class BookLine(
    val product: Product,
    val cartons: Int? = null,
    val pieces: Int? = null,
    val price: Long? = null,
) {
    val totalPieces: Int get() = (cartons ?: 0) * product.piecesPerCarton.coerceAtLeast(1) + (pieces ?: 0)

    // Excluding VAT, rounded like the server. Zero until both a quantity and a price are typed.
    val amount: Long get() = lineAmount(totalPieces, price ?: 0, product.piecesPerCarton)
}

// The sums for one company on the order, which becomes one invoice.
data class CompanyTotals(
    val code: String,
    val subtotal: Long,
    val vatRate: Double,
    val vat: Long,
    val total: Long,
)

data class BookOrderState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val loadError: String? = null,
    val booking: BookingInfo? = null,
    // F1 or F2: the warehouse the order comes from. Products may only come from its companies.
    val facility: String? = null,
    val invoiceType: String = "vat",
    // Active products per company code, for every company the shop buys from.
    val products: Map<String, List<Product>> = emptyMap(),
    // VAT rate in percent per company code.
    val vatRates: Map<String, Double> = emptyMap(),
    val lines: List<BookLine> = emptyList(),
    val lpos: Map<String, String> = emptyMap(),
    val wantedOn: String = "",
    val note: String = "",
    // Only admins and sales managers choose the salesman.
    val picksSalesman: Boolean = false,
    val salesmen: List<UserRow> = emptyList(),
    val salesmenLoading: Boolean = false,
    val salesmenError: String? = null,
    // The companies the salesmen list was loaded for.
    val salesmenFor: Set<String> = emptySet(),
    val salesmanId: Int? = null,
    // The product picker is open.
    val picking: Boolean = false,
    val query: String = "",
    val busy: Boolean = false,
    val error: String? = null,
) {
    // Each facility the shop's companies belong to, in the usual order.
    val facilities: List<String>
        get() = booking?.companies?.map { it.facilityCode }?.filter { it.isNotBlank() }?.distinct()?.sorted().orEmpty()

    // The shop's companies in the chosen facility, in the usual order.
    val facilityCompanies: List<BookingCompany>
        get() {
            val all = booking?.companies.orEmpty()
            val inFacility = if (facilities.size <= 1) all else all.filter { it.facilityCode == facility }
            return inFacility.sortedBy { companyOrder(it.code) }
        }

    // The companies that have at least one product on the order, in the usual order.
    val orderCompanies: List<String>
        get() = lines.map { it.product.companyCode }.distinct().sortedBy { companyOrder(it) }

    val totals: List<CompanyTotals>
        get() = orderCompanies.map { code ->
            val subtotal = lines.filter { it.product.companyCode == code }.sumOf { it.amount }
            val rate = vatRates[code] ?: DEFAULT_VAT_RATE
            val vat = if (invoiceType == "vat") vatOf(subtotal, rate) else 0L
            CompanyTotals(code, subtotal, rate, vat, subtotal + vat)
        }

    // The salesman must be chosen when there are salesmen to choose from.
    val salesmanRequired: Boolean
        get() = picksSalesman && orderCompanies.isNotEmpty() && salesmen.isNotEmpty()
}

// Where a company comes in the usual order (LV, ZT, ZF, GL).
internal fun companyOrder(code: String): Int = companyCodes.indexOf(code).let { if (it < 0) Int.MAX_VALUE else it }

sealed interface BookOrderEvent {
    data object Retry : BookOrderEvent
    data class FacilityChosen(val code: String) : BookOrderEvent
    data class InvoiceType(val type: String) : BookOrderEvent
    data class Cartons(val index: Int, val value: Int?) : BookOrderEvent
    data class Pieces(val index: Int, val value: Int?) : BookOrderEvent
    data class Price(val index: Int, val value: Long?) : BookOrderEvent
    data class RemoveLine(val index: Int) : BookOrderEvent
    data object OpenPicker : BookOrderEvent
    data object ClosePicker : BookOrderEvent
    data class Query(val value: String) : BookOrderEvent
    data class AddProduct(val product: Product) : BookOrderEvent
    data class Lpo(val company: String, val value: String) : BookOrderEvent
    data class WantedOn(val value: String) : BookOrderEvent
    data class Note(val value: String) : BookOrderEvent
    data class SalesmanChosen(val id: Int) : BookOrderEvent
    data object RetrySalesmen : BookOrderEvent
    data object Book : BookOrderEvent
}

sealed interface BookOrderEffect {
    data class Booked(val id: Int) : BookOrderEffect
}

// Step 2 of booking an order: products, prices, invoice type, LPO and delivery date for one shop.
class BookOrderViewModel(
    private val customerId: Int,
    private val repository: OrdersRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        BookOrderState(
            allowed = app.user?.canBookOrders() == true,
            picksSalesman = app.user?.picksSalesman() == true,
            wantedOn = tomorrowIsoDate(),
        ),
    )
    val state: StateFlow<BookOrderState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BookOrderEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<BookOrderEffect> = _effects.asSharedFlow()

    private var salesmenJob: Job? = null

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: BookOrderEvent) {
        when (event) {
            BookOrderEvent.Retry -> load()
            is BookOrderEvent.FacilityChosen -> if (event.code != _state.value.facility) {
                _state.update { it.copy(facility = event.code, lines = emptyList(), lpos = emptyMap(), error = null) }
                orderCompaniesChanged()
            }
            is BookOrderEvent.InvoiceType -> _state.update { it.copy(invoiceType = event.type, error = null) }
            is BookOrderEvent.Cartons -> editLine(event.index) { it.copy(cartons = event.value) }
            is BookOrderEvent.Pieces -> editLine(event.index) { it.copy(pieces = event.value) }
            is BookOrderEvent.Price -> editLine(event.index) { it.copy(price = event.value) }
            is BookOrderEvent.RemoveLine -> {
                _state.update { s ->
                    val lines = s.lines.filterIndexed { index, _ -> index != event.index }
                    val left = lines.map { it.product.companyCode }.toSet()
                    s.copy(lines = lines, lpos = s.lpos.filterKeys { it in left }, error = null)
                }
                orderCompaniesChanged()
            }
            BookOrderEvent.OpenPicker -> _state.update { it.copy(picking = true, query = "", error = null) }
            BookOrderEvent.ClosePicker -> _state.update { it.copy(picking = false) }
            is BookOrderEvent.Query -> _state.update { it.copy(query = event.value) }
            is BookOrderEvent.AddProduct -> {
                _state.update { s ->
                    if (s.lines.any { it.product.id == event.product.id }) {
                        s.copy(picking = false)
                    } else {
                        val price = s.booking?.lastPrices?.firstOrNull { it.productId == event.product.id }?.pricePerCarton
                        s.copy(lines = s.lines + BookLine(event.product, price = price), picking = false, error = null)
                    }
                }
                orderCompaniesChanged()
            }
            is BookOrderEvent.Lpo -> _state.update { it.copy(lpos = it.lpos + (event.company to event.value), error = null) }
            is BookOrderEvent.WantedOn -> _state.update { it.copy(wantedOn = event.value, error = null) }
            is BookOrderEvent.Note -> _state.update { it.copy(note = event.value, error = null) }
            is BookOrderEvent.SalesmanChosen -> _state.update { it.copy(salesmanId = event.id, error = null) }
            BookOrderEvent.RetrySalesmen -> loadSalesmen(_state.value.orderCompanies.toSet())
            BookOrderEvent.Book -> book()
        }
    }

    private fun editLine(index: Int, change: (BookLine) -> BookLine) {
        _state.update { s ->
            if (index !in s.lines.indices) s
            else s.copy(lines = s.lines.mapIndexed { i, line -> if (i == index) change(line) else line }, error = null)
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val booking = repository.booking(customerId)
                val codes = booking.companies.map { it.code }
                val (products, rates) = coroutineScope {
                    val productCalls = codes.map { code -> async { code to repository.products(code) } }
                    val rateCalls = codes.map { code -> async { code to vatRateOrDefault(code) } }
                    productCalls.awaitAll().toMap() to rateCalls.awaitAll().toMap()
                }
                val facilities = booking.companies.map { it.facilityCode }.filter { it.isNotBlank() }.distinct()
                _state.update {
                    it.copy(
                        loading = false,
                        booking = booking,
                        products = products,
                        vatRates = rates,
                        facility = it.facility ?: facilities.singleOrNull(),
                        invoiceType = if (booking.customer.lastInvoiceType == "plain") "plain" else "vat",
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = app.handleApiError(e)) }
            }
        }
    }

    // A missing rate should not stop the booking; the server works the VAT out anyway.
    private suspend fun vatRateOrDefault(code: String): Double = try {
        repository.vatRate(code)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        DEFAULT_VAT_RATE
    }

    // The salesman list depends on the companies on the order, so reload it when they change.
    private fun orderCompaniesChanged() {
        val s = _state.value
        if (!s.picksSalesman) return
        val companies = s.orderCompanies.toSet()
        if (companies == s.salesmenFor && s.salesmenError == null) return
        loadSalesmen(companies)
    }

    private fun loadSalesmen(companies: Set<String>) {
        salesmenJob?.cancel()
        if (companies.isEmpty()) {
            _state.update { it.copy(salesmen = emptyList(), salesmenFor = emptySet(), salesmenLoading = false, salesmenError = null, salesmanId = null) }
            return
        }
        _state.update { it.copy(salesmenFor = companies, salesmenLoading = true, salesmenError = null) }
        salesmenJob = viewModelScope.launch {
            try {
                val list = repository.salesmenFor(companies)
                _state.update { s ->
                    if (s.salesmenFor != companies) s
                    else s.copy(
                        salesmen = list,
                        salesmenLoading = false,
                        salesmanId = s.salesmanId?.takeIf { id -> list.any { it.id == id } } ?: list.singleOrNull()?.id,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { if (it.salesmenFor == companies) it.copy(salesmenLoading = false, salesmenError = message) else it }
            }
        }
    }

    private fun book() {
        val s = _state.value
        val booking = s.booking ?: return
        if (s.busy || s.salesmenLoading) return
        viewModelScope.launch {
            val problem = problemWith(s, booking)
            if (problem != null) {
                _state.update { it.copy(error = problem) }
                return@launch
            }
            _state.update { it.copy(busy = true, error = null) }
            val companies = s.orderCompanies.toSet()
            val request = NewOrderRequest(
                customerId = customerId,
                invoiceType = s.invoiceType,
                salesmanId = if (s.picksSalesman) s.salesmanId else null,
                wantedOn = s.wantedOn.ifBlank { null },
                note = s.note.trim(),
                lpos = s.lpos
                    .filterKeys { it in companies }
                    .mapValues { it.value.trim() }
                    .filterValues { it.isNotEmpty() },
                lines = s.lines.map {
                    NewOrderLine(
                        productId = it.product.id,
                        cartons = it.cartons ?: 0,
                        pieces = it.pieces ?: 0,
                        pricePerCarton = it.price ?: 0,
                    )
                },
            )
            try {
                val order = repository.book(request)
                app.message = getString(Res.string.order_book_booked, order.number)
                _effects.tryEmit(BookOrderEffect.Booked(order.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = app.handleApiError(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    // The first thing still missing, in plain words; null when the order can be sent.
    private suspend fun problemWith(s: BookOrderState, booking: BookingInfo): String? {
        if (s.lines.isEmpty()) return getString(Res.string.order_book_err_lines)
        s.lines.forEach { line ->
            val title = formProductTitle(line.product.brand, line.product.name, line.product.packSize)
            if (line.totalPieces <= 0) return getString(Res.string.order_book_err_qty, title)
            if ((line.price ?: 0) <= 0) return getString(Res.string.order_book_err_price, title)
        }
        if (booking.customer.needsLpo) {
            s.orderCompanies.forEach { code ->
                if (s.lpos[code].isNullOrBlank()) return getString(Res.string.order_book_err_lpo, companyName(code))
            }
        }
        if (s.picksSalesman && s.salesmenError != null) return s.salesmenError
        if (s.salesmanRequired && s.salesmen.none { it.id == s.salesmanId }) return getString(Res.string.order_book_err_salesman)
        return null
    }
}
