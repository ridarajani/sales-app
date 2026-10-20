package com.salesapp.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.Driver
import com.salesapp.data.ListsRepository
import com.salesapp.data.Optional
import com.salesapp.data.Vehicle
import com.salesapp.data.VehicleRequest
import com.salesapp.data.vehicleChange
import com.salesapp.resources.Res
import com.salesapp.resources.catalog_added
import com.salesapp.resources.catalog_saved
import com.salesapp.resources.vehicle_err_plate
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

data class VehicleFormState(
    val isEdit: Boolean = false,
    val loading: Boolean = true,
    val loadError: String? = null,
    val drivers: List<Driver> = emptyList(),
    val plate: String = "",
    val description: String = "",
    // "F1", "F2" or null for no facility.
    val facility: String? = null,
    val driverId: Int? = null,
    val active: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface VehicleFormEvent {
    data class Plate(val value: String) : VehicleFormEvent
    data class Description(val value: String) : VehicleFormEvent
    data class Facility(val code: String?) : VehicleFormEvent
    data class DriverChosen(val id: Int?) : VehicleFormEvent
    data class Active(val value: Boolean) : VehicleFormEvent
    data object Save : VehicleFormEvent
    data object Retry : VehicleFormEvent
}

class VehicleFormViewModel(
    private val id: Int?,
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(VehicleFormState(isEdit = id != null))
    val state: StateFlow<VehicleFormState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FormEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<FormEffect> = _effects.asSharedFlow()

    private var loaded: Vehicle? = null

    init {
        load()
    }

    fun onEvent(event: VehicleFormEvent) {
        when (event) {
            is VehicleFormEvent.Plate -> _state.update { it.copy(plate = event.value.uppercase(), error = null) }
            is VehicleFormEvent.Description -> _state.update { it.copy(description = event.value, error = null) }
            is VehicleFormEvent.Facility -> _state.update { it.copy(facility = event.code) }
            is VehicleFormEvent.DriverChosen -> _state.update { it.copy(driverId = event.id) }
            is VehicleFormEvent.Active -> _state.update { it.copy(active = event.value) }
            VehicleFormEvent.Save -> save()
            VehicleFormEvent.Retry -> load()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val drivers = repository.drivers()
                val vehicle = if (id != null) repository.vehicles().firstOrNull { it.id == id } else null
                loaded = vehicle
                _state.update {
                    if (vehicle == null) {
                        it.copy(loading = false, drivers = drivers)
                    } else {
                        it.copy(
                            loading = false,
                            drivers = drivers,
                            plate = vehicle.plate,
                            description = vehicle.description,
                            facility = vehicle.facilityCode,
                            driverId = vehicle.defaultDriverId,
                            active = vehicle.active,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = app.handleApiError(e)) }
            }
        }
    }

    private fun save() {
        val s = _state.value
        if (s.busy) return
        // Mark as saving at once so a double tap cannot save twice.
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            if (s.plate.isBlank()) {
                _state.update { it.copy(busy = false, error = getString(Res.string.vehicle_err_plate)) }
                return@launch
            }
            try {
                val old = loaded
                if (id == null) {
                    val added = repository.createVehicle(
                        VehicleRequest(
                            plate = s.plate.trim(),
                            description = s.description.trim(),
                            facilityCode = s.facility,
                            defaultDriverId = s.driverId,
                        ),
                    )
                    app.message = getString(Res.string.catalog_added, added.plate)
                } else {
                    repository.updateVehicle(
                        id,
                        vehicleChange(
                            plate = s.plate.trim().takeIf { old == null || it != old.plate },
                            description = s.description.trim().takeIf { old == null || it != old.description },
                            facilityCode = if (old == null || s.facility != old.facilityCode) Optional.Present(s.facility) else Optional.Absent,
                            defaultDriverId = if (old == null || s.driverId != old.defaultDriverId) Optional.Present(s.driverId) else Optional.Absent,
                            active = s.active.takeIf { old == null || it != old.active },
                        ),
                    )
                    app.message = getString(Res.string.catalog_saved)
                }
                _effects.tryEmit(FormEffect.Done)
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
