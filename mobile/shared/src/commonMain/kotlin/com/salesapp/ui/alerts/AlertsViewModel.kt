package com.salesapp.ui.alerts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AlertCounts
import com.salesapp.data.AlertRow
import com.salesapp.data.AlertsRepository
import com.salesapp.data.AppState
import com.salesapp.ui.navigation.alertRouteFor
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

// Everything that needs someone now, worst first, for the companies this person can see.
data class AlertsState(
    val loading: Boolean = true,
    val refreshing: Boolean = false,
    val alerts: List<AlertRow> = emptyList(),
    val counts: AlertCounts = AlertCounts(),
    val error: String? = null,
) {
    val none: Boolean get() = !loading && error == null && alerts.isEmpty()
}

sealed interface AlertsEvent {
    data object Retry : AlertsEvent
    data object Refresh : AlertsEvent
    data class Open(val alert: AlertRow) : AlertsEvent
}

sealed interface AlertsEffect {
    data class Open(val route: Any) : AlertsEffect
}

class AlertsViewModel(
    private val repository: AlertsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(AlertsState())
    val state: StateFlow<AlertsState> = _state.asStateFlow()

    private var loadJob: Job? = null

    private val _effects = MutableSharedFlow<AlertsEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<AlertsEffect> = _effects.asSharedFlow()

    init {
        load(showSpinner = true)
    }

    fun onEvent(event: AlertsEvent) {
        when (event) {
            AlertsEvent.Retry -> load(showSpinner = true)
            // Pulling the list down asks again without blanking what is already on screen.
            AlertsEvent.Refresh -> load(showSpinner = false)
            // An alert this person cannot open does nothing at all.
            is AlertsEvent.Open -> alertRouteFor(event.alert, app.user)?.let { _effects.tryEmit(AlertsEffect.Open(it)) }
        }
    }

    private fun load(showSpinner: Boolean) {
        loadJob?.cancel()
        _state.update { it.copy(loading = showSpinner, refreshing = !showSpinner, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val result = repository.alerts()
                _state.update {
                    it.copy(loading = false, refreshing = false, alerts = result.alerts, counts = result.counts, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, refreshing = false, error = app.handleApiError(e)) }
            }
        }
    }
}
