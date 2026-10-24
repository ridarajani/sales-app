package com.salesapp.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.todayIsoDate
import com.salesapp.data.AppState
import com.salesapp.data.Company
import com.salesapp.data.Driver
import com.salesapp.data.PlanTripRequest
import com.salesapp.data.Product
import com.salesapp.data.Role
import com.salesapp.data.TripDetail
import com.salesapp.data.TripLineQty
import com.salesapp.data.TripsRepository
import com.salesapp.data.UserRow
import com.salesapp.data.Vehicle
import com.salesapp.data.canPlanTrips
import com.salesapp.resources.Res
import com.salesapp.resources.plan_changed
import com.salesapp.resources.plan_need_leader
import com.salesapp.resources.plan_need_lines
import com.salesapp.resources.plan_need_route
import com.salesapp.resources.plan_saved
import com.salesapp.resources.plan_deleted
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

// One product being put on the trip.
data class PlanLine(val product: Product, val cartons: Int? = null, val pieces: Int? = null) {
    val perCarton: Int get() = product.piecesPerCarton.coerceAtLeast(1)

    val total: Int get() = (cartons ?: 0) * perCarton + (pieces ?: 0)

    val label: String get() = listOf(product.brand, product.name, product.packSize).filter { it.isNotBlank() }.joinToString(" ")
}

data class PlanTripState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    // Null when a new trip is being planned.
    val tripId: Int? = null,
    val kind: String = "upcountry",
    val facilities: List<String> = emptyList(),
    val facilityCode: String? = null,
    val companies: List<Company> = emptyList(),
    val companyCode: String? = null,
    val tripDate: String = "",
    val route: String = "",
    val stops: List<String> = emptyList(),
    val stopDraft: String = "",
    val vehicles: List<Vehicle> = emptyList(),
    val vehicleId: Int? = null,
    val drivers: List<Driver> = emptyList(),
    val driverId: Int? = null,
    val leaders: List<UserRow> = emptyList(),
    val leaderId: Int? = null,
    val staff: String = "",
    val float: Long? = null,
    val note: String = "",
    val lines: List<PlanLine> = emptyList(),
    val asking: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
) {
    val anything: Boolean get() = lines.any { it.total > 0 }

    // The companies of the chosen warehouse that this user may plan for.
    val facilityCompanies: List<Company> get() = companies.filter { it.facilityCode == facilityCode }
}

sealed interface PlanTripEvent {
    data class KindChosen(val kind: String) : PlanTripEvent
    data class FacilityChosen(val code: String) : PlanTripEvent
    data class CompanyChosen(val code: String) : PlanTripEvent
    data class DateChanged(val iso: String) : PlanTripEvent
    data class RouteChanged(val value: String) : PlanTripEvent
    data class StopDraftChanged(val value: String) : PlanTripEvent
    data object AddStop : PlanTripEvent
    data class RemoveStop(val stop: String) : PlanTripEvent
    data class VehicleChosen(val id: Int) : PlanTripEvent
    data class DriverChosen(val id: Int) : PlanTripEvent
    data class LeaderChosen(val id: Int) : PlanTripEvent
    data class StaffChanged(val value: String) : PlanTripEvent
    data class FloatChanged(val value: Long?) : PlanTripEvent
    data class NoteChanged(val value: String) : PlanTripEvent
    data class EditCartons(val productId: Int, val value: Int?) : PlanTripEvent
    data class EditPieces(val productId: Int, val value: Int?) : PlanTripEvent
    data object Save : PlanTripEvent
    data object AskDelete : PlanTripEvent
    data object Dismiss : PlanTripEvent
    data object Delete : PlanTripEvent
    data object Retry : PlanTripEvent
}

sealed interface PlanTripEffect {
    data class Saved(val tripId: Int) : PlanTripEffect

    data object Deleted : PlanTripEffect
}

class PlanTripViewModel(
    private val tripId: Int?,
    private val repository: TripsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        PlanTripState(allowed = app.user?.canPlanTrips() == true, tripId = tripId, tripDate = todayIsoDate()),
    )
    val state: StateFlow<PlanTripState> = _state.asStateFlow()

    private val _effects = Channel<PlanTripEffect>(Channel.BUFFERED)
    val effects: Flow<PlanTripEffect> = _effects.receiveAsFlow()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: PlanTripEvent) {
        val s = _state.value
        if (!s.allowed || s.busy && event != PlanTripEvent.Retry) return
        when (event) {
            is PlanTripEvent.KindChosen -> _state.update { it.copy(kind = event.kind, actionError = null) }
            is PlanTripEvent.FacilityChosen -> {
                if (event.code == s.facilityCode) return
                // Another warehouse means other companies, products, vehicles and leaders.
                _state.update { it.copy(facilityCode = event.code, companyCode = null, vehicleId = null, leaderId = null, lines = emptyList(), actionError = null) }
                loadFacility(event.code)
            }
            is PlanTripEvent.CompanyChosen -> _state.update { it.copy(companyCode = event.code, actionError = null) }
            is PlanTripEvent.DateChanged -> _state.update { it.copy(tripDate = event.iso, actionError = null) }
            is PlanTripEvent.RouteChanged -> _state.update { it.copy(route = event.value, actionError = null) }
            is PlanTripEvent.StopDraftChanged -> _state.update { it.copy(stopDraft = event.value) }
            PlanTripEvent.AddStop -> _state.update {
                val stop = it.stopDraft.trim()
                if (stop.isEmpty() || stop in it.stops) it.copy(stopDraft = "") else it.copy(stops = it.stops + stop, stopDraft = "")
            }
            is PlanTripEvent.RemoveStop -> _state.update { it.copy(stops = it.stops - event.stop) }
            is PlanTripEvent.VehicleChosen -> _state.update {
                // A vehicle brings its usual driver along, unless there is one already.
                val usual = it.vehicles.firstOrNull { v -> v.id == event.id }?.defaultDriverId
                it.copy(vehicleId = event.id, driverId = it.driverId ?: usual, actionError = null)
            }
            is PlanTripEvent.DriverChosen -> _state.update { it.copy(driverId = event.id, actionError = null) }
            is PlanTripEvent.LeaderChosen -> _state.update { it.copy(leaderId = event.id, actionError = null) }
            is PlanTripEvent.StaffChanged -> _state.update { it.copy(staff = event.value) }
            is PlanTripEvent.FloatChanged -> _state.update { it.copy(float = event.value) }
            is PlanTripEvent.NoteChanged -> _state.update { it.copy(note = event.value) }
            is PlanTripEvent.EditCartons -> edit(event.productId) { it.copy(cartons = event.value) }
            is PlanTripEvent.EditPieces -> edit(event.productId) { it.copy(pieces = event.value) }
            PlanTripEvent.Save -> save()
            PlanTripEvent.AskDelete -> if (tripId != null) _state.update { it.copy(asking = true) }
            PlanTripEvent.Dismiss -> _state.update { it.copy(asking = false) }
            PlanTripEvent.Delete -> delete()
            PlanTripEvent.Retry -> load()
        }
    }

    private fun edit(productId: Int, change: (PlanLine) -> PlanLine) {
        _state.update { s ->
            s.copy(lines = s.lines.map { if (it.product.id == productId) change(it) else it }, actionError = null)
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch {
            val facility = s.facilityCode
            if (facility == null || s.route.isBlank()) {
                _state.update { it.copy(actionError = getString(Res.string.plan_need_route)) }
                return@launch
            }
            val leader = s.leaderId
            if (leader == null) {
                _state.update { it.copy(actionError = getString(Res.string.plan_need_leader)) }
                return@launch
            }
            if (!s.anything) {
                _state.update { it.copy(actionError = getString(Res.string.plan_need_lines)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val request = PlanTripRequest(
                    kind = s.kind,
                    facilityCode = facility,
                    companyCode = s.companyCode,
                    tripDate = s.tripDate,
                    route = s.route.trim(),
                    stops = s.stops,
                    vehicleId = s.vehicleId,
                    driverId = s.driverId,
                    leaderId = leader,
                    staff = s.staff.trim(),
                    note = s.note.trim(),
                    float = (s.float ?: 0).toInt(),
                    lines = s.lines.filter { it.total > 0 }.map {
                        TripLineQty(productId = it.product.id, cartons = it.cartons ?: 0, pieces = it.pieces ?: 0)
                    },
                )
                val saved = if (tripId == null) repository.plan(request) else repository.change(tripId, request)
                app.message = if (tripId == null) getString(Res.string.plan_saved, saved.number) else getString(Res.string.plan_changed)
                _effects.trySend(PlanTripEffect.Saved(saved.id))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server names the line or the person that is wrong.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun delete() {
        val id = tripId ?: return
        _state.update { it.copy(busy = true, asking = false, actionError = null) }
        viewModelScope.launch {
            try {
                repository.delete(id)
                app.message = getString(Res.string.plan_deleted)
                _effects.trySend(PlanTripEffect.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val companies = repository.myFacilities()
                val facilities = companies.map { it.facilityCode }.filter { it.isNotBlank() }.distinct().sorted()
                val existing = tripId?.let { repository.trip(it) }
                val facility = existing?.facilityCode ?: facilities.firstOrNull()
                _state.update {
                    it.copy(
                        companies = companies,
                        facilities = facilities,
                        facilityCode = facility,
                        kind = existing?.kind ?: it.kind,
                        companyCode = existing?.companyCode,
                        tripDate = existing?.tripDate ?: it.tripDate,
                        route = existing?.route ?: it.route,
                        stops = existing?.stops ?: it.stops,
                        vehicleId = existing?.vehicleId,
                        driverId = existing?.driverId,
                        leaderId = existing?.leaderId,
                        staff = existing?.staff ?: it.staff,
                        float = existing?.float?.toLong() ?: it.float,
                        note = existing?.note ?: it.note,
                    )
                }
                if (facility != null) loadFacility(facility, existing)
                else _state.update { it.copy(loading = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    // The vehicles, drivers, leaders and products of one warehouse.
    private fun loadFacility(facilityCode: String, existing: TripDetail? = null) {
        viewModelScope.launch {
            try {
                val codes = _state.value.companies.filter { it.facilityCode == facilityCode }.map { it.code }
                val vehicles = repository.vehicles(facilityCode)
                val drivers = repository.drivers()
                val leaders = repository.possibleLeaders(codes)
                val products = repository.products().filter { it.active && it.companyCode in codes }
                _state.update { s ->
                    s.copy(
                        loading = false,
                        vehicles = vehicles,
                        drivers = drivers,
                        leaders = leaders,
                        companyCode = s.companyCode ?: codes.firstOrNull(),
                        lines = products.map { product ->
                            // On a trip being changed, start from what was planned.
                            val planned = existing?.lines?.firstOrNull { it.productId == product.id }
                            PlanLine(product, cartons = planned?.plannedQty?.cartons, pieces = planned?.plannedQty?.loosePieces)
                        },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
