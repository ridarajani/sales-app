package com.salesapp.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.epochDayFromIso
import com.salesapp.core.isoFromEpochDay
import com.salesapp.core.todayIsoDate
import com.salesapp.data.AppState
import com.salesapp.data.Product
import com.salesapp.data.ReceiptDetail
import com.salesapp.data.ReceiptLineRequest
import com.salesapp.data.ReceiptRequest
import com.salesapp.data.StockRepository
import com.salesapp.data.canKeepStock
import com.salesapp.resources.Res
import com.salesapp.resources.receive_err_batch
import com.salesapp.resources.receive_err_duplicate
import com.salesapp.resources.receive_err_expired
import com.salesapp.resources.receive_err_expiry
import com.salesapp.resources.receive_err_product
import com.salesapp.resources.number_err_quantity
import com.salesapp.ui.catalog.FormEffect
import com.salesapp.ui.catalog.companiesOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class ReceiveLine(
    val product: Product,
    val batchNo: String,
    val expiryDate: String,
    val cartons: Int,
    val pieces: Int,
)

data class EditingLine(
    val product: Product? = null,
    val query: String = "",
    val batchNo: String = "",
    val expiryDate: String = "",
    val cartons: Int? = null,
    val pieces: Int? = null,
    val error: String? = null,
)

data class ReceiveStockState(
    val allowed: Boolean = true,
    val companies: List<String> = emptyList(),
    val company: String? = null,
    val supplier: String = "",
    val deliveryNote: String = "",
    val receivedOn: String = "",
    val products: List<Product> = emptyList(),
    val productsLoading: Boolean = false,
    val productsError: String? = null,
    val lines: List<ReceiveLine> = emptyList(),
    val editing: EditingLine? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val receipt: ReceiptDetail? = null,
)

sealed interface ReceiveStockEvent {
    data class CompanyChosen(val code: String) : ReceiveStockEvent
    data class Supplier(val value: String) : ReceiveStockEvent
    data class DeliveryNote(val value: String) : ReceiveStockEvent
    data class ReceivedOn(val value: String) : ReceiveStockEvent
    data class RemoveLine(val index: Int) : ReceiveStockEvent
    data object OpenEditor : ReceiveStockEvent
    data object CloseEditor : ReceiveStockEvent
    data class Query(val value: String) : ReceiveStockEvent
    data class ProductChosen(val product: Product?) : ReceiveStockEvent
    data class BatchNo(val value: String) : ReceiveStockEvent
    data class Expiry(val value: String) : ReceiveStockEvent
    data class Cartons(val value: Int?) : ReceiveStockEvent
    data class Pieces(val value: Int?) : ReceiveStockEvent
    data object AddLine : ReceiveStockEvent
    data object Save : ReceiveStockEvent
    data object RetryProducts : ReceiveStockEvent
    data object Finish : ReceiveStockEvent
}

class ReceiveStockViewModel(
    private val repository: StockRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(ReceiveStockState())
    val state: StateFlow<ReceiveStockState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FormEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<FormEffect> = _effects.asSharedFlow()

    private var productsJob: Job? = null

    init {
        val user = app.user
        val companies = companiesOf(user)
        _state.value = ReceiveStockState(
            allowed = user?.canKeepStock() == true,
            companies = companies,
            receivedOn = todayIsoDate(),
        )
        if (_state.value.allowed && companies.size == 1) chooseCompany(companies.first())
    }

    fun onEvent(event: ReceiveStockEvent) {
        when (event) {
            is ReceiveStockEvent.CompanyChosen -> if (event.code != _state.value.company) chooseCompany(event.code)
            is ReceiveStockEvent.Supplier -> _state.update { it.copy(supplier = event.value, error = null) }
            is ReceiveStockEvent.DeliveryNote -> _state.update { it.copy(deliveryNote = event.value, error = null) }
            is ReceiveStockEvent.ReceivedOn -> _state.update { it.copy(receivedOn = event.value, error = null) }
            is ReceiveStockEvent.RemoveLine -> _state.update {
                it.copy(lines = it.lines.filterIndexed { index, _ -> index != event.index }, error = null)
            }
            ReceiveStockEvent.OpenEditor -> _state.update {
                it.copy(editing = EditingLine(expiryDate = defaultExpiry()), error = null)
            }
            ReceiveStockEvent.CloseEditor -> _state.update { it.copy(editing = null) }
            is ReceiveStockEvent.Query -> edit { it.copy(query = event.value) }
            is ReceiveStockEvent.ProductChosen -> edit { it.copy(product = event.product, error = null) }
            is ReceiveStockEvent.BatchNo -> edit { it.copy(batchNo = event.value, error = null) }
            is ReceiveStockEvent.Expiry -> edit { it.copy(expiryDate = event.value, error = null) }
            is ReceiveStockEvent.Cartons -> edit { it.copy(cartons = event.value, error = null) }
            is ReceiveStockEvent.Pieces -> edit { it.copy(pieces = event.value, error = null) }
            ReceiveStockEvent.AddLine -> addLine()
            ReceiveStockEvent.Save -> save()
            ReceiveStockEvent.RetryProducts -> _state.value.company?.let { loadProducts(it) }
            ReceiveStockEvent.Finish -> _effects.tryEmit(FormEffect.Done)
        }
    }

    private fun edit(change: (EditingLine) -> EditingLine) {
        _state.update { s -> s.editing?.let { s.copy(editing = change(it)) } ?: s }
    }

    private fun defaultExpiry(): String = isoFromEpochDay((epochDayFromIso(todayIsoDate()) ?: 0L) + 365)

    private fun chooseCompany(code: String) {
        _state.update { it.copy(company = code, lines = emptyList(), editing = null, products = emptyList(), error = null) }
        loadProducts(code)
    }

    private fun loadProducts(code: String) {
        productsJob?.cancel()
        productsJob = viewModelScope.launch {
            _state.update { it.copy(productsLoading = true, productsError = null) }
            try {
                val list = repository.products(code).filter { it.active }
                _state.update { if (it.company == code) it.copy(productsLoading = false, products = list) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(productsLoading = false, productsError = app.handleApiError(e)) }
            }
        }
    }

    private fun addLine() {
        val s = _state.value
        val line = s.editing ?: return
        viewModelScope.launch {
            val product = line.product
            val batchNo = line.batchNo.trim().uppercase()
            val problem = when {
                product == null -> getString(Res.string.receive_err_product)
                batchNo.isEmpty() -> getString(Res.string.receive_err_batch)
                line.expiryDate.isBlank() -> getString(Res.string.receive_err_expiry)
                line.expiryDate <= todayIsoDate() -> getString(Res.string.receive_err_expired)
                (line.cartons ?: 0) * product.piecesPerCarton + (line.pieces ?: 0) <= 0 -> getString(Res.string.number_err_quantity)
                s.lines.any { it.product.id == product.id && it.batchNo == batchNo } -> getString(Res.string.receive_err_duplicate)
                else -> null
            }
            if (problem != null || product == null) {
                edit { it.copy(error = problem) }
                return@launch
            }
            val added = ReceiveLine(product, batchNo, line.expiryDate, line.cartons ?: 0, line.pieces ?: 0)
            _state.update { it.copy(lines = it.lines + added, editing = null, error = null) }
        }
    }

    private fun save() {
        val s = _state.value
        val company = s.company ?: return
        if (s.busy || s.lines.isEmpty()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val receipt = repository.receive(
                    ReceiptRequest(
                        companyCode = company,
                        supplier = s.supplier.trim(),
                        reference = s.deliveryNote.trim(),
                        receivedOn = s.receivedOn,
                        lines = s.lines.map {
                            ReceiptLineRequest(it.product.id, it.batchNo, it.expiryDate, it.cartons, it.pieces)
                        },
                    ),
                )
                _state.update { it.copy(receipt = receipt) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = app.handleApiError(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
