package com.salesapp.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.ListsRepository
import com.salesapp.data.Vehicle
import com.salesapp.data.canEditVehicles
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class VehiclesState(
    val loading: Boolean = true,
    val vehicles: List<Vehicle> = emptyList(),
    val error: String? = null,
    val canEdit: Boolean = false,
)

sealed interface VehiclesEvent {
    data object Retry : VehiclesEvent
    data object Refresh : VehiclesEvent
    data class OpenVehicle(val id: Int) : VehiclesEvent
    data object AddVehicle : VehiclesEvent
}

sealed interface VehiclesEffect {
    data class OpenVehicle(val id: Int) : VehiclesEffect
    data object AddVehicle : VehiclesEffect
}

class VehiclesViewModel(
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(VehiclesState(canEdit = app.session.user.value?.canEditVehicles() == true))
    val state: StateFlow<VehiclesState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<VehiclesEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<VehiclesEffect> = _effects.asSharedFlow()

    init {
        load(showSpinner = true)
    }

    fun onEvent(event: VehiclesEvent) {
        when (event) {
            VehiclesEvent.Retry -> load(showSpinner = true)
            VehiclesEvent.Refresh -> if (!_state.value.loading && _state.value.error == null) load(showSpinner = false)
            is VehiclesEvent.OpenVehicle -> if (_state.value.canEdit) _effects.tryEmit(VehiclesEffect.OpenVehicle(event.id))
            VehiclesEvent.AddVehicle -> _effects.tryEmit(VehiclesEffect.AddVehicle)
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.vehicles()
                _state.update { it.copy(loading = false, vehicles = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
