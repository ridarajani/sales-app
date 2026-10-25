package com.salesapp.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.LoadTripRequest
import com.salesapp.data.TripDetail
import com.salesapp.data.TripLineQty
import com.salesapp.data.TripOrder
import com.salesapp.data.TripOutbox
import com.salesapp.data.TripSource
import com.salesapp.data.canLoadTrips
import com.salesapp.data.canSeeTrips
import com.salesapp.resources.Res
import com.salesapp.resources.trip_load_need_vehicle
import com.salesapp.resources.trip_sent_all
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// One product as the warehouse types what actually went on the van.
data class LoadLine(val productId: Int, val label: String, val perCarton: Int, val cartons: Int?, val pieces: Int?) {
    val total: Int get() = (cartons ?: 0) * perCarton.coerceAtLeast(1) + (pieces ?: 0)
}

data class TripDetailState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val trip: TripDetail? = null,
    // What this phone is still holding for the trip.
    val outbox: TripOutbox = TripOutbox(),
    // True when the trip on screen came from the phone, not the server.
    val fromPhone: Boolean = false,
    // Something saved on this phone could not be read and is waiting for the office.
    val hasUnreadable: Boolean = false,
    // The warehouse's own form.
    val loadLines: List<LoadLine> = emptyList(),
    val loadVehicleId: Int? = null,
    val vehicles: List<com.salesapp.data.Vehicle> = emptyList(),
    val loading2: Boolean = false,
    val busy: Boolean = false,
    val syncing: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
) {
    private val perCarton: Map<Int, Int>
        get() = trip?.lines.orEmpty().associate { it.productId to it.piecesPerCarton } +
            trip?.orders.orEmpty().flatMap { it.lines }.associate { it.productId to it.perCarton }

    // What is on the van, less anything this phone has sold but not sent yet.
    val onVehicle: Map<Int, Int>
        get() {
            val waiting = outbox.soldPieces(perCarton)
            return trip?.lines.orEmpty().associate { it.productId to (it.onVehicle - (waiting[it.productId] ?: 0)).coerceAtLeast(0) }
        }

    // Of that, what the van is holding for the trip's own orders.
    val forOrders: Map<Int, Int>
        get() {
            val waiting = outbox.orderSoldPieces(perCarton)
            return trip?.lines.orEmpty().associate { it.productId to (it.forOrders - (waiting[it.productId] ?: 0)).coerceAtLeast(0) }
        }

    // And what may be sold to anybody at a stop.
    val forSale: Map<Int, Int>
        get() {
            val waiting = outbox.plainSoldPieces(perCarton)
            return trip?.lines.orEmpty().associate { it.productId to (it.sellable - (waiting[it.productId] ?: 0)).coerceAtLeast(0) }
        }

    // What is still to deliver on one order, less the deliveries waiting on this phone.
    fun toDeliver(order: TripOrder): Map<Int, Int> {
        val waiting = outbox.orderSoldPieces(perCarton, order.id)
        return order.lines.associate { it.productId to (it.toDeliver - (waiting[it.productId] ?: 0)).coerceAtLeast(0) }
    }

    // True when an order's delivery is sitting in the outbox, so the card says so.
    fun waitingOn(order: TripOrder): Boolean = outbox.sales.any { it.request.orderId == order.id }

    // Sales this phone could not read, now with the office. The trip cannot close until Admin has
    // looked at them, which the server also refuses.
    val outboxProblemOpen: Boolean get() = (trip?.openOutboxProblems ?: 0) > 0

    // Closing is only allowed once everything on the phone has reached the server.
    val canClose: Boolean get() = trip?.canClose == true && outbox.isEmpty && !outboxProblemOpen

    val blockedByOutbox: Boolean get() = trip?.canClose == true && !outbox.isEmpty
}

sealed interface TripDetailEvent {
    data object Retry : TripDetailEvent
    data object Refresh : TripDetailEvent
    data class EditLoadCartons(val productId: Int, val value: Int?) : TripDetailEvent
    data class EditLoadPieces(val productId: Int, val value: Int?) : TripDetailEvent
    data class LoadVehicleChosen(val id: Int) : TripDetailEvent
    data object Load : TripDetailEvent
    data object SendNow : TripDetailEvent
    data class DropSale(val requestId: String) : TripDetailEvent
    data class DropExpense(val requestId: String) : TripDetailEvent
}

class TripDetailViewModel(
    private val id: Int,
    private val repository: TripSource,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(TripDetailState(allowed = app.user?.canSeeTrips() == true))
    val state: StateFlow<TripDetailState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        if (_state.value.allowed) {
            // Whatever the phone is holding shows at once, before the server is asked.
            _state.update { it.copy(outbox = repository.outboxOf(id), hasUnreadable = repository.hasUnreadable(id)) }
            load()
        } else {
            _state.update { it.copy(loading = false) }
        }
    }

    fun onEvent(event: TripDetailEvent) {
        val s = _state.value
        if (!s.allowed || s.busy && event != TripDetailEvent.Retry) return
        when (event) {
            TripDetailEvent.Retry -> load()
            TripDetailEvent.Refresh -> {
                _state.update { it.copy(outbox = repository.outboxOf(id), hasUnreadable = repository.hasUnreadable(id)) }
                if (!s.loading) load(showSpinner = false)
            }
            is TripDetailEvent.EditLoadCartons -> editLoad(event.productId) { it.copy(cartons = event.value) }
            is TripDetailEvent.EditLoadPieces -> editLoad(event.productId) { it.copy(pieces = event.value) }
            is TripDetailEvent.LoadVehicleChosen -> _state.update { it.copy(loadVehicleId = event.id, actionError = null) }
            TripDetailEvent.Load -> loadVan()
            TripDetailEvent.SendNow -> sync()
            is TripDetailEvent.DropSale -> _state.update { it.copy(outbox = repository.dropSale(id, event.requestId)) }
            is TripDetailEvent.DropExpense -> _state.update { it.copy(outbox = repository.dropExpense(id, event.requestId)) }
        }
    }

    private fun editLoad(productId: Int, change: (LoadLine) -> LoadLine) {
        _state.update { s ->
            s.copy(loadLines = s.loadLines.map { if (it.productId == productId) change(it) else it }, actionError = null)
        }
    }

    // The warehouse says what actually went on; the van leaves at once.
    private fun loadVan() {
        val s = _state.value
        val trip = s.trip ?: return
        if (s.busy || !trip.canLoad) return
        viewModelScope.launch {
            val vehicle = s.loadVehicleId ?: trip.vehicleId
            if (vehicle == null) {
                _state.update { it.copy(actionError = getString(Res.string.trip_load_need_vehicle)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val saved = repository.load(
                    id,
                    LoadTripRequest(
                        vehicleId = vehicle,
                        lines = s.loadLines.map { TripLineQty(it.productId, cartons = it.cartons ?: 0, pieces = it.pieces ?: 0) },
                    ),
                )
                _state.update { it.copy(busy = false, trip = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server says when nothing on the shelves is free.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    // Everything the phone is holding, in one go.
    private fun sync() {
        val s = _state.value
        if (s.syncing || s.outbox.isEmpty) return
        _state.update { it.copy(syncing = true, actionError = null) }
        viewModelScope.launch {
            try {
                val outcome = repository.sync(id)
                app.message = if (outcome.outbox.isEmpty) getString(Res.string.trip_sent_all) else null
                _state.update {
                    it.copy(
                        syncing = false,
                        outbox = outcome.outbox,
                        trip = outcome.trip ?: it.trip,
                        // The server's own words for anything it would not take.
                        actionError = outcome.refused.firstOrNull(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(syncing = false, actionError = message) }
            }
        }
    }

    private fun load(showSpinner: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                var trip = repository.trip(id)
                // The leader of a trip on the road keeps everything needed to sell with no network.
                if (trip.canSell) runCatching { repository.pack(id) }
                // The network is up, so any sales file this phone could not read goes to the office
                // now, and the files go only once the office has cleared them all.
                if (repository.hasUnreadable(id)) {
                    val sent = runCatching { repository.sendOutboxProblems(id) }.getOrDefault(0)
                    // A file that has just gone up gives the trip an open problem, so it is asked
                    // for again before the screen is drawn: otherwise Close trip would still be
                    // offered for a trip the server would refuse to close.
                    if (sent > 0) trip = runCatching { repository.trip(id) }.getOrDefault(trip)
                    repository.forgetClearedProblems(id, trip.outboxProblems)
                }
                val vehicles = if (trip.canLoad) runCatching { repository.vehicles(trip.facilityCode) }.getOrDefault(emptyList()) else emptyList()
                _state.update {
                    it.copy(
                        loading = false,
                        trip = trip,
                        fromPhone = false,
                        vehicles = vehicles,
                        loadVehicleId = it.loadVehicleId ?: trip.vehicleId,
                        outbox = repository.outboxOf(id),
                        hasUnreadable = repository.hasUnreadable(id),
                        error = null,
                        // The warehouse's form starts at what was planned.
                        loadLines = if (trip.canLoad) {
                            trip.lines.map { line ->
                                LoadLine(line.productId, line.label, line.piecesPerCarton, line.plannedQty.cartons, line.plannedQty.loosePieces)
                            }
                        } else {
                            it.loadLines
                        },
                    )
                }
                // A closed trip has nothing left to keep on the phone.
                if (trip.closed) repository.forget(id)
                // Reaching the server at all means the network is back, so anything the phone is
                // still holding goes up now without waiting for the button.
                if (!trip.closed && repository.outboxOf(id).isNotEmpty) sync()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No network: fall back to what this phone saved, so the trip can still be sold from.
                val saved = repository.savedPack(id)
                if (saved != null) {
                    _state.update {
                        it.copy(
                            loading = false,
                            trip = saved.trip,
                            fromPhone = true,
                            outbox = repository.outboxOf(id),
                            hasUnreadable = repository.hasUnreadable(id),
                            error = null,
                        )
                    }
                } else {
                    val message = app.handleApiError(e)
                    _state.update { if (it.trip == null) it.copy(loading = false, error = message) else it.copy(loading = false) }
                }
            }
        }
    }
}
