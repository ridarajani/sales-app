package com.salesapp.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.TripListItem
import com.salesapp.data.TripsRepository
import com.salesapp.data.canPlanTrips
import com.salesapp.data.canSeeTrips
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripsState(
    val allowed: Boolean = true,
    val canPlan: Boolean = false,
    val loading: Boolean = true,
    // null = every trip; otherwise planned, out or closed.
    val status: String? = null,
    val trips: List<TripListItem> = emptyList(),
    val error: String? = null,
    // The warehouse sees no money.
    val showsMoney: Boolean = true,
)

sealed interface TripsEvent {
    data class StatusChosen(val status: String?) : TripsEvent
    data object Retry : TripsEvent
    data object Refresh : TripsEvent
}

class TripsViewModel(
    private val repository: TripsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        TripsState(
            allowed = app.user?.canSeeTrips() == true,
            canPlan = app.user?.canPlanTrips() == true,
            showsMoney = app.user?.role != "warehouse",
        ),
    )
    val state: StateFlow<TripsState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: TripsEvent) {
        if (!_state.value.allowed) return
        when (event) {
            is TripsEvent.StatusChosen -> {
                if (event.status == _state.value.status) return
                _state.update { it.copy(status = event.status, trips = emptyList()) }
                load()
            }
            TripsEvent.Retry -> load()
            TripsEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
        }
    }

    private fun load(showSpinner: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.trips(_state.value.status, null)
                loadedOnce = true
                _state.update { it.copy(loading = false, trips = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
