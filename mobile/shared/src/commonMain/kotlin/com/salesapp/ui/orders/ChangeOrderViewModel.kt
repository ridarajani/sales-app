package com.salesapp.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.DEFAULT_VAT_RATE
import com.salesapp.core.lineAmount
import com.salesapp.core.tomorrowIsoDate
import com.salesapp.core.vatOf
import com.salesapp.data.AppState
import com.salesapp.data.OrderChange
import com.salesapp.data.OrderDetail
import com.salesapp.data.OrderLine
import com.salesapp.data.OrderLineChange
import com.salesapp.data.OrdersRepository
import com.salesapp.resources.Res
import com.salesapp.resources.order_change_done
import com.salesapp.resources.order_change_err_empty
import com.salesapp.resources.order_change_err_price
import com.salesapp.resources.order_change_nothing
import com.salesapp.ui.catalog.FormEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// One product line of the order as it is being changed. Blank boxes are null.
data class ChangeLine(
    val lineId: Int,
    val title: String,
    val companyCode: String,
    val piecesPerCarton: Int,
    val cartons: Int?,
    val pieces: Int?,
    val price: Long?,
) {
    val totalPieces: Int get() = (cartons ?: 0) * piecesPerCarton.coerceAtLeast(1) + (pieces ?: 0)
    val amount: Long get() = lineAmount(totalPieces, price ?: 0, piecesPerCarton)
}

// Money for one company on the order, worked out on the phone the same way as the server.
data class ChangeCompanyTotal(
    val code: String,
    val subtotal: Long,
    val vatRate: Double,
    val vat: Long,
    val total: Long,
)

data class ChangeOrderState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val order: OrderDetail? = null,
    val invoiceType: String = "vat",
    val lines: List<ChangeLine> = emptyList(),
    val lpos: Map<String, String> = emptyMap(),
    val wantedOn: String = "",
    val note: String = "",
    val vatRates: Map<String, Double> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    // Saved (or nothing to save) and the form is closing; Save does nothing more.
    val done: Boolean = false,
) {
    val canChange: Boolean get() = order?.canChange == true

    // The order's companies in the order the server gave them.
    val companyCodes: List<String>
        get() = order?.companies?.map { it.code }?.ifEmpty { null } ?: lines.map { it.companyCode }.distinct()

    val totals: List<ChangeCompanyTotal>
        get() = companyCodes.map { code ->
            val subtotal = lines.filter { it.companyCode == code }.sumOf { it.amount }
            val rate = vatRates[code] ?: DEFAULT_VAT_RATE
            val vat = if (invoiceType == "vat") vatOf(subtotal, rate) else 0L
            ChangeCompanyTotal(code, subtotal, rate, vat, subtotal + vat)
        }

    val grandTotal: Long get() = totals.sumOf { it.total }
}

sealed interface ChangeOrderEvent {
    data class InvoiceType(val value: String) : ChangeOrderEvent
    data class Cartons(val lineId: Int, val value: Int?) : ChangeOrderEvent
    data class Pieces(val lineId: Int, val value: Int?) : ChangeOrderEvent
    data class Price(val lineId: Int, val value: Long?) : ChangeOrderEvent
    data class Lpo(val code: String, val value: String) : ChangeOrderEvent
    data class WantedOn(val value: String) : ChangeOrderEvent
    data class Note(val value: String) : ChangeOrderEvent
    data object Save : ChangeOrderEvent
    data object Retry : ChangeOrderEvent
}

private fun changeLineTitle(line: OrderLine): String =
    listOf(line.brand, line.name, line.packSize).filter { it.isNotBlank() }.joinToString(" ")

internal fun changeLinesOf(order: OrderDetail): List<ChangeLine> = order.lines.map {
    ChangeLine(
        lineId = it.id,
        title = changeLineTitle(it),
        companyCode = it.companyCode,
        piecesPerCarton = it.piecesPerCarton,
        cartons = it.cartons,
        pieces = it.loosePieces,
        price = it.pricePerCarton,
    )
}

class ChangeOrderViewModel(
    private val id: Int,
    private val repository: OrdersRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(ChangeOrderState())
    val state: StateFlow<ChangeOrderState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FormEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<FormEffect> = _effects.asSharedFlow()

    init {
        load()
    }

    fun onEvent(event: ChangeOrderEvent) {
        when (event) {
            is ChangeOrderEvent.InvoiceType -> _state.update { it.copy(invoiceType = event.value, error = null) }
            is ChangeOrderEvent.Cartons -> editLine(event.lineId) { it.copy(cartons = event.value) }
            is ChangeOrderEvent.Pieces -> editLine(event.lineId) { it.copy(pieces = event.value) }
            is ChangeOrderEvent.Price -> editLine(event.lineId) { it.copy(price = event.value) }
            is ChangeOrderEvent.Lpo -> _state.update { it.copy(lpos = it.lpos + (event.code to event.value), error = null) }
            is ChangeOrderEvent.WantedOn -> _state.update { it.copy(wantedOn = event.value, error = null) }
            is ChangeOrderEvent.Note -> _state.update { it.copy(note = event.value, error = null) }
            ChangeOrderEvent.Save -> save()
            ChangeOrderEvent.Retry -> load()
        }
    }

    private fun editLine(lineId: Int, change: (ChangeLine) -> ChangeLine) {
        _state.update { s ->
            s.copy(lines = s.lines.map { if (it.lineId == lineId) change(it) else it }, error = null)
        }
    }

    private fun load() {
        _state.update { it.copy(loading = true, loadError = null) }
        viewModelScope.launch {
            try {
                val order = repository.order(id)
                val rates = vatRatesFor(order)
                _state.value = ChangeOrderState(
                    loading = false,
                    order = order,
                    invoiceType = order.invoiceType,
                    lines = changeLinesOf(order),
                    lpos = order.companies.associate { it.code to (it.lpoNo ?: "") },
                    // An order with no date shows tomorrow; saving then gives it that date.
                    wantedOn = order.wantedOn ?: tomorrowIsoDate(),
                    note = order.note,
                    vatRates = rates,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = app.handleApiError(e)) }
            }
        }
    }

    // A VAT order already carries each company's rate; a plain one has 0, so ask for the company's rate.
    private suspend fun vatRatesFor(order: OrderDetail): Map<String, Double> =
        order.companies.associate { company ->
            val rate = if (order.invoiceType == "vat") {
                company.vatRate
            } else {
                try {
                    repository.vatRate(company.code)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DEFAULT_VAT_RATE
                }
            }
            company.code to rate
        }

    private fun save() {
        val s = _state.value
        val order = s.order ?: return
        if (s.busy || s.done || !s.canChange) return
        viewModelScope.launch {
            val noPrice = s.lines.firstOrNull { (it.price ?: 0) <= 0 }
            val problem = when {
                noPrice != null -> getString(Res.string.order_change_err_price, noPrice.title)
                s.lines.none { it.totalPieces > 0 } -> getString(Res.string.order_change_err_empty)
                else -> null
            }
            if (problem != null) {
                _state.update { it.copy(error = problem) }
                return@launch
            }
            val change = changeOf(order, s)
            if (change == OrderChange()) {
                _state.update { it.copy(done = true) }
                app.message = getString(Res.string.order_change_nothing)
                _effects.tryEmit(FormEffect.Done)
                return@launch
            }
            _state.update { it.copy(busy = true, error = null) }
            try {
                repository.change(id, change)
                // Stays busy while the form closes, so the change is not sent twice.
                _state.update { it.copy(done = true) }
                app.message = getString(Res.string.order_change_done)
                _effects.tryEmit(FormEffect.Done)
            } catch (e: CancellationException) {
                _state.update { it.copy(busy = false) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, error = app.handleApiError(e)) }
            }
        }
    }

    // Only what differs from the order as the server holds it.
    private fun changeOf(order: OrderDetail, s: ChangeOrderState): OrderChange {
        val before = order.lines.associateBy { it.id }
        val lines = s.lines.filter { line ->
            val old = before[line.lineId]
            old == null || old.pieces != line.totalPieces || old.pricePerCarton != (line.price ?: 0)
        }.map { OrderLineChange(it.lineId, it.cartons ?: 0, it.pieces ?: 0, it.price ?: 0) }
        val lpos = order.companies.mapNotNull { company ->
            val typed = s.lpos[company.code]?.trim() ?: return@mapNotNull null
            if (typed != (company.lpoNo ?: "").trim()) company.code to typed else null
        }.toMap()
        val note = s.note.trim()
        return OrderChange(
            invoiceType = s.invoiceType.takeIf { it != order.invoiceType },
            wantedOn = s.wantedOn.takeIf { it.isNotBlank() && it != order.wantedOn },
            note = note.takeIf { it != order.note.trim() },
            lpos = lpos.ifEmpty { null },
            lines = lines.ifEmpty { null },
        )
    }
}
