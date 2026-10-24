package com.salesapp.ui.loading

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Documents
import com.salesapp.core.decodeImage
import com.salesapp.data.AppState
import com.salesapp.data.LoadingRepository
import com.salesapp.data.MadeInvoice
import com.salesapp.data.PlanDetail
import com.salesapp.data.ServerFile
import com.salesapp.data.User
import com.salesapp.data.canSeePlans
import com.salesapp.data.isDriver
import com.salesapp.data.seesPlanChanges
import com.salesapp.resources.Res
import com.salesapp.resources.invoice_no_viewer
import com.salesapp.ui.navigation.ConfirmDeliveryRoute
import com.salesapp.ui.navigation.InvoiceDetailRoute
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class TripState(
    val loading: Boolean = true,
    val plan: PlanDetail? = null,
    val user: User? = null,
    val error: String? = null,
    val allowed: Boolean = true,
    // An action is being sent ("OK, seen", the invoices, a document); every button is off.
    val busy: Boolean = false,
    // A problem from an action, shown under the changes.
    val actionError: String? = null,
    // Phase 6: what the last "Make invoices" made, and the pictures of each confirmed drop.
    val made: List<MadeInvoice> = emptyList(),
    val signatures: Map<Int, ImageBitmap> = emptyMap(),
    val photos: Map<Int, ImageBitmap> = emptyMap(),
    val imagesFailed: Boolean = false,
) {
    // Only planners and the warehouse see the changes and may mark them seen; the flag is shared,
    // so drivers, accounts and cashier never touch it.
    val seesChanges: Boolean get() = user?.seesPlanChanges() == true

    // Drivers only see the drops; everyone else also gets the product totals.
    val showsProducts: Boolean get() = user?.isDriver() != true

    // The delivery note and gate pass exist once the vehicle is loaded.
    val hasDocuments: Boolean get() = plan?.deliveryNoteNo?.isNotBlank() == true

    // How many pieces a carton holds, per product, for the delivered amounts on a drop.
    val perCarton: Map<Int, Int> get() = plan?.products.orEmpty().associate { it.productId to it.piecesPerCarton }
}

sealed interface TripEvent {
    data object Retry : TripEvent
    data object Refresh : TripEvent
    data object Seen : TripEvent
    // Phase 6.
    data object MakeInvoices : TripEvent
    data object DeliveryNote : TripEvent
    data object GatePass : TripEvent
    data class OpenDrop(val customerId: Int) : TripEvent
    data class OpenInvoice(val id: Int) : TripEvent
}

sealed interface TripEffect {
    // A screen to open: a drop to confirm, or one invoice.
    data class Open(val route: Any) : TripEffect
}

class TripViewModel(
    private val id: Int,
    private val repository: LoadingRepository,
    private val documents: Documents,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(TripState(user = app.user, allowed = app.user?.canSeePlans() == true))
    val state: StateFlow<TripState> = _state.asStateFlow()

    private val _effects = Channel<TripEffect>(Channel.BUFFERED)
    val effects: Flow<TripEffect> = _effects.receiveAsFlow()

    init {
        if (_state.value.allowed) load(showSpinner = true) else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: TripEvent) {
        if (!_state.value.allowed) return
        when (event) {
            TripEvent.Retry -> load(showSpinner = true)
            TripEvent.Refresh -> {
                val s = _state.value
                if (s.plan != null && !s.loading && !s.busy) load(showSpinner = false)
            }
            TripEvent.Seen -> seen()
            TripEvent.MakeInvoices -> makeInvoices()
            TripEvent.DeliveryNote -> document("delivery-note") { repository.deliveryNote(id) }
            TripEvent.GatePass -> document("gate-pass") { repository.gatePass(id) }
            is TripEvent.OpenDrop -> {
                // Only the driver of this vehicle, or an admin, may confirm a drop.
                if (_state.value.plan?.canDeliver == true) _effects.trySend(TripEffect.Open(ConfirmDeliveryRoute(id, event.customerId)))
            }
            is TripEvent.OpenInvoice -> _effects.trySend(TripEffect.Open(InvoiceDetailRoute(event.id)))
        }
    }

    // Makes the invoices for this user's companies; the reply says what it made.
    private fun makeInvoices() {
        val s = _state.value
        if (s.busy || s.plan?.canMakeInvoices != true) return
        _state.update { it.copy(busy = true, actionError = null, made = emptyList()) }
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

    // Fetches one of the vehicle's documents and opens it in the phone's own PDF viewer.
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

    // The signature, and the photo when there is one, of every confirmed drop.
    private fun loadImages(plan: PlanDetail) {
        val deliveries = plan.drops.mapNotNull { it.delivery }
        if (deliveries.isEmpty()) return
        viewModelScope.launch {
            deliveries.forEach { delivery ->
                if (_state.value.signatures[delivery.id] == null) {
                    picture({ repository.signature(delivery.id) }) { image ->
                        _state.update { it.copy(signatures = it.signatures + (delivery.id to image)) }
                    }
                }
                if (delivery.hasPhoto && _state.value.photos[delivery.id] == null) {
                    picture({ repository.photo(delivery.id) }) { image ->
                        _state.update { it.copy(photos = it.photos + (delivery.id to image)) }
                    }
                }
            }
        }
    }

    private suspend fun picture(fetch: suspend () -> ServerFile, onLoaded: (ImageBitmap) -> Unit) {
        try {
            val image = decodeImage(fetch().bytes)
            if (image == null) _state.update { it.copy(imagesFailed = true) } else onLoaded(image)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The rest of the drop still shows; only the picture is missing.
            _state.update { it.copy(imagesFailed = true) }
        }
    }

    private fun seen() {
        val s = _state.value
        if (s.busy || !s.seesChanges || s.plan == null) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val plan = repository.seen(id)
                _state.update { it.copy(plan = plan, busy = false) }
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
                _state.update { it.copy(loading = false, plan = plan, error = null, user = app.user) }
                loadImages(plan)
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
