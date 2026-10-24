package com.salesapp.ui.loading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Documents
import com.salesapp.data.AppState
import com.salesapp.data.LoadingRepository
import com.salesapp.data.MadeInvoice
import com.salesapp.data.NotLoaded
import com.salesapp.data.PlanDetail
import com.salesapp.data.PlanProduct
import com.salesapp.data.ProductQty
import com.salesapp.data.ServerFile
import com.salesapp.data.User
import com.salesapp.data.WarehouseChangeRequest
import com.salesapp.data.canSeePlans
import com.salesapp.resources.Res
import com.salesapp.resources.invoice_no_viewer
import com.salesapp.resources.loading_wh_change_saved
import com.salesapp.resources.loading_wh_reason_needed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// The two steps that ask "are you sure?" first.
enum class WarehouseAsk { Load, Gate }

// One product's new total being typed in, with the reason.
data class ProductEdit(
    val product: PlanProduct,
    val cartons: Int? = product.cartons,
    val pieces: Int? = product.loosePieces,
    val reason: String = "",
    val error: String? = null,
) {
    // Something was typed that a quiet refresh must not throw away.
    val dirty: Boolean
        get() = (cartons ?: 0) != product.cartons || (pieces ?: 0) != product.loosePieces || reason.isNotBlank()
}

// What a warehouse change could not add, with the name and carton size looked up from the plan before the change.
data class NotFit(val label: String, val pieces: Int, val piecesPerCarton: Int)

data class WarehousePlanState(
    val loading: Boolean = true,
    val plan: PlanDetail? = null,
    val user: User? = null,
    val error: String? = null,
    val allowed: Boolean = true,
    // An action is being sent; every action button is off.
    val busy: Boolean = false,
    val asking: WarehouseAsk? = null,
    // A problem from an action, shown above the buttons.
    val actionError: String? = null,
    // Set while one product's cartons are being changed.
    val edit: ProductEdit? = null,
    // From the last warehouse change; stays until the next action.
    val notFit: List<NotFit> = emptyList(),
    // From the Loaded reply; a refresh does not send it again, so it is kept here.
    val notLoaded: List<NotLoaded> = emptyList(),
    // Phase 6: what the last "Make invoices" made.
    val made: List<MadeInvoice> = emptyList(),
) {
    val editing: Boolean get() = edit != null

    // The delivery note and gate pass exist once the vehicle is loaded.
    val hasDocuments: Boolean get() = plan?.deliveryNoteNo?.isNotBlank() == true
}

sealed interface WarehousePlanEvent {
    data object Retry : WarehousePlanEvent
    data object Refresh : WarehousePlanEvent
    data object Seen : WarehousePlanEvent
    data class StartEdit(val productId: Int) : WarehousePlanEvent
    data class EditCartons(val value: Int?) : WarehousePlanEvent
    data class EditPieces(val value: Int?) : WarehousePlanEvent
    data class EditReason(val value: String) : WarehousePlanEvent
    data object SaveEdit : WarehousePlanEvent
    data object CloseEdit : WarehousePlanEvent
    data class Ask(val what: WarehouseAsk) : WarehousePlanEvent
    data object Confirm : WarehousePlanEvent
    data object Dismiss : WarehousePlanEvent
    // Phase 6.
    data object MakeInvoices : WarehousePlanEvent
    data object DeliveryNote : WarehousePlanEvent
    data object GatePass : WarehousePlanEvent
}

class WarehousePlanViewModel(
    private val id: Int,
    private val repository: LoadingRepository,
    private val documents: Documents,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(WarehousePlanState(user = app.user, allowed = app.user?.canSeePlans() == true))
    val state: StateFlow<WarehousePlanState> = _state.asStateFlow()

    init {
        if (_state.value.allowed) load(showSpinner = true) else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: WarehousePlanEvent) {
        if (!_state.value.allowed) return
        when (event) {
            WarehousePlanEvent.Retry -> load(showSpinner = true)
            WarehousePlanEvent.Refresh -> {
                val s = _state.value
                if (s.plan != null && !s.loading && !s.busy && s.edit?.dirty != true) load(showSpinner = false)
            }
            WarehousePlanEvent.Seen -> act { repository.seen(id) }
            is WarehousePlanEvent.StartEdit -> {
                val s = _state.value
                val product = s.plan?.products?.firstOrNull { it.productId == event.productId } ?: return
                if (s.busy || s.plan.canWarehouseChange.not()) return
                _state.update { it.copy(edit = ProductEdit(product), notFit = emptyList(), actionError = null) }
            }
            is WarehousePlanEvent.EditCartons -> _state.update { s -> s.copy(edit = s.edit?.copy(cartons = event.value)) }
            is WarehousePlanEvent.EditPieces -> _state.update { s -> s.copy(edit = s.edit?.copy(pieces = event.value)) }
            is WarehousePlanEvent.EditReason -> _state.update { s -> s.copy(edit = s.edit?.copy(reason = event.value, error = null)) }
            WarehousePlanEvent.CloseEdit -> if (!_state.value.busy) _state.update { it.copy(edit = null, actionError = null) }
            WarehousePlanEvent.SaveEdit -> saveEdit()
            is WarehousePlanEvent.Ask -> {
                val s = _state.value
                if (s.busy || s.editing) return
                _state.update { it.copy(asking = event.what) }
            }
            WarehousePlanEvent.Confirm -> _state.value.asking?.let { what ->
                _state.update { it.copy(asking = null) }
                when (what) {
                    WarehouseAsk.Load -> act(keepNotLoaded = false) { repository.load(id) }
                    WarehouseAsk.Gate -> act { repository.gate(id) }
                }
            }
            WarehousePlanEvent.Dismiss -> _state.update { it.copy(asking = null) }
            WarehousePlanEvent.MakeInvoices -> makeInvoices()
            WarehousePlanEvent.DeliveryNote -> document("delivery-note") { repository.deliveryNote(id) }
            WarehousePlanEvent.GatePass -> document("gate-pass") { repository.gatePass(id) }
        }
    }

    // Admin is an invoice maker, and lands here while the vehicle is loaded.
    private fun makeInvoices() {
        val s = _state.value
        if (s.busy || s.editing || s.plan?.canMakeInvoices != true) return
        _state.update { it.copy(busy = true, actionError = null, made = emptyList(), notFit = emptyList()) }
        viewModelScope.launch {
            try {
                val plan = repository.makeInvoices(id)
                _state.update { it.copy(plan = plan, busy = false, made = plan.made) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    // One of the vehicle's documents, opened in the phone's own PDF viewer.
    private fun document(name: String, fetch: suspend () -> ServerFile) {
        val plan = _state.value.plan ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val file = fetch()
                val opened = documents.open("${plan.number}-$name.pdf", file.bytes, file.contentType, share = false)
                _state.update { it.copy(busy = false, actionError = if (opened) null else getString(Res.string.invoice_no_viewer)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun saveEdit() {
        val s = _state.value
        val edit = s.edit ?: return
        if (s.busy) return
        val reason = edit.reason.trim()
        viewModelScope.launch {
            if (reason.isEmpty()) {
                val text = getString(Res.string.loading_wh_reason_needed)
                _state.update { it.copy(edit = it.edit?.copy(error = text)) }
                return@launch
            }
            val before = s.plan?.products.orEmpty()
            _state.update { it.copy(busy = true, actionError = null, notFit = emptyList()) }
            try {
                val request = WarehouseChangeRequest(
                    products = listOf(ProductQty(edit.product.productId, cartons = edit.cartons ?: 0, pieces = edit.pieces ?: 0)),
                    reason = reason,
                )
                val plan = repository.warehouseChange(id, request)
                val notFit = plan.notAdded.filter { it.pieces > 0 }.map { missing ->
                    val product = before.firstOrNull { it.productId == missing.productId }
                        ?: edit.product.takeIf { it.productId == missing.productId }
                    NotFit(
                        label = product?.let { productLabel(it) }.orEmpty(),
                        pieces = missing.pieces,
                        piecesPerCarton = product?.piecesPerCarton ?: 1,
                    )
                }
                _state.update { it.copy(plan = plan, busy = false, edit = null, notFit = notFit) }
                if (notFit.isEmpty()) app.message = getString(Res.string.loading_wh_change_saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    // Sends one action and shows the plan it returns. Every action clears the "did not fit" notes.
    private fun act(keepNotLoaded: Boolean = true, call: suspend () -> PlanDetail) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null, notFit = emptyList()) }
        viewModelScope.launch {
            try {
                val plan = call()
                _state.update {
                    it.copy(plan = plan, busy = false, notLoaded = if (keepNotLoaded) it.notLoaded else plan.notLoaded)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val plan = repository.plan(id)
                _state.update { s ->
                    // Keep an open edit on the product as it is now; drop it if the product left the plan.
                    val edit = s.edit?.let { e -> plan.products.firstOrNull { it.productId == e.product.productId }?.let { ProductEdit(it) } }
                    s.copy(loading = false, plan = plan, error = null, user = app.user, edit = if (plan.canWarehouseChange) edit else null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                // A quiet refresh that fails keeps the plan already on screen.
                _state.update { if (it.plan == null) it.copy(loading = false, error = message) else it.copy(loading = false) }
            }
        }
    }
}

internal fun productLabel(product: PlanProduct): String =
    listOf(product.brand, product.name, product.packSize).filter { it.isNotBlank() }.joinToString(" ")
