package com.salesapp.ui.loading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.Driver
import com.salesapp.data.LoadingRepository
import com.salesapp.data.OpenLine
import com.salesapp.data.OpenProduct
import com.salesapp.data.PlanDetail
import com.salesapp.data.PlanItem
import com.salesapp.data.PlanItemQty
import com.salesapp.data.PlanItemsRequest
import com.salesapp.data.User
import com.salesapp.data.Vehicle
import com.salesapp.data.canPlanLoading
import com.salesapp.ui.navigation.OrderDetailRoute
import com.salesapp.resources.Res
import com.salesapp.resources.loading_pl_deleted
import com.salesapp.resources.loading_pl_saved
import com.salesapp.resources.loading_pl_sent_ok
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// The two steps that ask "are you sure?" first.
enum class PlannerAsk { Send, Delete }

// One order line's amount as it is being typed: cartons plus loose pieces on top.
data class Picked(val cartons: Int? = null, val pieces: Int? = null) {
    fun total(piecesPerCarton: Int): Int = (cartons ?: 0) * piecesPerCarton + (pieces ?: 0)
}

data class PlannerPlanState(
    val loading: Boolean = true,
    val plan: PlanDetail? = null,
    val open: List<OpenProduct> = emptyList(),
    val vehicles: List<Vehicle> = emptyList(),
    val drivers: List<Driver> = emptyList(),
    val user: User? = null,
    val allowed: Boolean = true,
    val error: String? = null,
    // An action is being sent; every button is off.
    val busy: Boolean = false,
    val asking: PlannerAsk? = null,
    // A problem from an action, shown above the buttons.
    val actionError: String? = null,
    // The vehicle and driver picked but not saved yet; null means "as the plan has it".
    val vehicleId: Int? = null,
    val driverId: Int? = null,
    // lineId -> what was typed for that line, only while it is unsaved.
    val picked: Map<Int, Picked> = emptyMap(),
) {
    val chosenVehicle: Int? get() = vehicleId ?: plan?.vehicleId
    val chosenDriver: Int? get() = driverId ?: plan?.driverId

    // What a line's fields show: what was typed, or what the plan carries now.
    fun pickedFor(line: OpenLine): Picked =
        picked[line.lineId] ?: Picked(line.currentCartons.cartons, line.currentCartons.loosePieces)

    // Something was typed or picked that is not on the server yet.
    val dirty: Boolean
        get() = picked.isNotEmpty() || (vehicleId != null && vehicleId != plan?.vehicleId) || (driverId != null && driverId != plan?.driverId)

    // Lines still on the plan whose order has since gone on hold or been closed, so they are no
    // longer among the open lines. They have to come off before the plan can be sent.
    val leftOut: List<PlanItem>
        get() {
            val plan = plan ?: return emptyList()
            val openIds = open.flatMap { it.lines }.map { it.lineId }.toSet()
            return plan.products.flatMap { it.items }.filter { it.lineId !in openIds && it.pieces > 0 }
        }

    val hasItems: Boolean get() = plan?.products?.any { it.pieces > 0 } == true
}

sealed interface PlannerPlanEvent {
    data object Retry : PlannerPlanEvent
    data object Refresh : PlannerPlanEvent
    data object Seen : PlannerPlanEvent
    data class VehicleChosen(val id: Int) : PlannerPlanEvent
    data class DriverChosen(val id: Int) : PlannerPlanEvent
    data class EditCartons(val lineId: Int, val value: Int?) : PlannerPlanEvent
    data class EditPieces(val lineId: Int, val value: Int?) : PlannerPlanEvent
    // Puts every open carton of one product on the plan.
    data class AllOn(val productId: Int) : PlannerPlanEvent
    // Takes one line off the plan, for an order that is not open any more.
    data class TakeOff(val lineId: Int) : PlannerPlanEvent
    // Opens the order a line belongs to.
    data class OpenOrder(val orderId: Int) : PlannerPlanEvent
    data object Save : PlannerPlanEvent
    data class Ask(val what: PlannerAsk) : PlannerPlanEvent
    data object Confirm : PlannerPlanEvent
    data object Dismiss : PlannerPlanEvent
}

sealed interface PlannerPlanEffect {
    // The plan is gone, so the screen closes.
    data object Deleted : PlannerPlanEffect

    // Another screen to open, such as the order a line belongs to.
    data class Open(val route: Any) : PlannerPlanEffect
}

class PlannerPlanViewModel(
    private val id: Int,
    private val repository: LoadingRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(PlannerPlanState(user = app.user, allowed = app.user?.canPlanLoading() == true))
    val state: StateFlow<PlannerPlanState> = _state.asStateFlow()

    private val _effects = Channel<PlannerPlanEffect>(Channel.BUFFERED)
    val effects: Flow<PlannerPlanEffect> = _effects.receiveAsFlow()

    init {
        if (_state.value.allowed) load(showSpinner = true) else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: PlannerPlanEvent) {
        if (!_state.value.allowed) return
        val s = _state.value
        when (event) {
            PlannerPlanEvent.Retry -> load(showSpinner = true)
            // A quiet refresh never throws away what is being typed.
            PlannerPlanEvent.Refresh -> if (s.plan != null && !s.loading && !s.busy && !s.dirty) load(showSpinner = false)
            PlannerPlanEvent.Seen -> act { repository.seen(id) }
            is PlannerPlanEvent.VehicleChosen -> if (!s.busy) {
                // A vehicle brings its usual driver along, unless there is a driver already.
                val usual = s.vehicles.firstOrNull { it.id == event.id }?.defaultDriverId
                val driver = s.chosenDriver ?: usual
                _state.update { it.copy(vehicleId = event.id, driverId = driver, actionError = null) }
            }
            is PlannerPlanEvent.DriverChosen -> if (!s.busy) _state.update { it.copy(driverId = event.id, actionError = null) }
            is PlannerPlanEvent.EditCartons -> if (!s.busy) edit(event.lineId) { it.copy(cartons = event.value) }
            is PlannerPlanEvent.EditPieces -> if (!s.busy) edit(event.lineId) { it.copy(pieces = event.value) }
            is PlannerPlanEvent.AllOn -> if (!s.busy) allOn(event.productId)
            is PlannerPlanEvent.TakeOff -> if (!s.busy) takeOff(event.lineId)
            is PlannerPlanEvent.OpenOrder -> _effects.trySend(PlannerPlanEffect.Open(OrderDetailRoute(event.orderId)))
            PlannerPlanEvent.Save -> save(thenSend = false)
            is PlannerPlanEvent.Ask -> if (!s.busy) _state.update { it.copy(asking = event.what) }
            PlannerPlanEvent.Confirm -> s.asking?.let { what ->
                _state.update { it.copy(asking = null) }
                // Sending saves what is on screen first, so nothing typed is lost.
                when (what) {
                    PlannerAsk.Send -> save(thenSend = true)
                    PlannerAsk.Delete -> delete()
                }
            }
            PlannerPlanEvent.Dismiss -> _state.update { it.copy(asking = null) }
        }
    }

    private fun edit(lineId: Int, change: (Picked) -> Picked) {
        _state.update { s ->
            val line = s.open.flatMap { it.lines }.firstOrNull { it.lineId == lineId } ?: return@update s
            s.copy(picked = s.picked + (lineId to change(s.pickedFor(line))), actionError = null)
        }
    }

    private fun allOn(productId: Int) {
        _state.update { s ->
            val product = s.open.firstOrNull { it.productId == productId } ?: return@update s
            val filled = product.lines.associate { it.lineId to Picked(it.openCartons.cartons, it.openCartons.loosePieces) }
            s.copy(picked = s.picked + filled, actionError = null)
        }
    }

    // A line whose order is no longer open can only be set to nothing, so this saves it on its own.
    private fun takeOff(lineId: Int) {
        act { repository.saveItems(id, PlanItemsRequest(items = listOf(PlanItemQty(lineId, cartons = 0, pieces = 0)))) }
    }

    // Saves the vehicle, the driver and every line that changed, then sends the plan when asked to.
    private fun save(thenSend: Boolean) {
        val s = _state.value
        val plan = s.plan ?: return
        if (s.busy) return
        val items = s.open.flatMap { product ->
            val perCarton = product.piecesPerCarton.coerceAtLeast(1)
            product.lines.mapNotNull { line ->
                val wanted = s.pickedFor(line).total(perCarton)
                if (wanted == line.current) {
                    null
                } else {
                    PlanItemQty(line.lineId, cartons = wanted / perCarton, pieces = wanted % perCarton)
                }
            }
        }
        val request = PlanItemsRequest(
            vehicleId = s.vehicleId?.takeIf { it != plan.vehicleId },
            driverId = s.driverId?.takeIf { it != plan.driverId },
            items = items.takeIf { it.isNotEmpty() },
        )
        val nothingToSave = request.vehicleId == null && request.driverId == null && request.items == null
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val saved = if (nothingToSave) plan else repository.saveItems(id, request)
                _state.update { it.copy(plan = saved, picked = emptyMap(), vehicleId = null, driverId = null) }
                if (thenSend) {
                    val sent = repository.send(id)
                    _state.update { it.copy(plan = sent, busy = false) }
                    app.message = getString(Res.string.loading_pl_sent_ok)
                } else {
                    _state.update { it.copy(busy = false) }
                    if (!nothingToSave) app.message = getString(Res.string.loading_pl_saved)
                }
                // The open lines move with the plan, so they are fetched again.
                refreshOpen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun delete() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                repository.delete(id)
                app.message = getString(Res.string.loading_pl_deleted)
                _effects.trySend(PlannerPlanEffect.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    // Sends one action and shows the plan it returns.
    private fun act(call: suspend () -> PlanDetail) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val plan = call()
                _state.update { it.copy(plan = plan, busy = false) }
                refreshOpen()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    // The open lines on their own; a failure here leaves the plan on screen as it is.
    private suspend fun refreshOpen() {
        try {
            val open = repository.openLines(id)
            _state.update { it.copy(open = open) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The plan is still right; the list of open orders just stays as it was.
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val plan = repository.plan(id)
                val open = repository.openLines(id)
                // The vehicles and drivers to choose from only matter while the plan can still change.
                val vehicles = if (plan.canPlan) repository.vehicles(plan.facilityCode) else emptyList()
                val drivers = if (plan.canPlan) repository.drivers() else emptyList()
                _state.update {
                    it.copy(loading = false, plan = plan, open = open, vehicles = vehicles, drivers = drivers, error = null, user = app.user)
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
