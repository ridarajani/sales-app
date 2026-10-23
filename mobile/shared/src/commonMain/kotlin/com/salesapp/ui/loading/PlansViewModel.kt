package com.salesapp.ui.loading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.LoadingPlanListItem
import com.salesapp.data.LoadingRepository
import com.salesapp.data.canPlanLoading
import com.salesapp.data.canSeePlans
import com.salesapp.data.isDriver
import com.salesapp.data.seesPlanChanges
import com.salesapp.ui.navigation.planRouteFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PlansState(
    val allowed: Boolean = true,
    // Drivers see "My trips" and a driver's empty message.
    val isDriver: Boolean = false,
    val canPlan: Boolean = false,
    // Only the planner and warehouse sides get the "New changes" pill.
    val seesChanges: Boolean = false,
    // null = all plans; otherwise planned, loaded, out or done.
    val status: String? = null,
    val loading: Boolean = true,
    val plans: List<LoadingPlanListItem> = emptyList(),
    val error: String? = null,
)

sealed interface PlansEvent {
    data class StatusChosen(val status: String?) : PlansEvent
    data object Retry : PlansEvent
    data object Refresh : PlansEvent
    data class Open(val plan: LoadingPlanListItem) : PlansEvent
    data object NewPlan : PlansEvent
}

sealed interface PlansEffect {
    // The route to open, picked by planRouteFor.
    data class Open(val route: Any) : PlansEffect
    data object NewPlan : PlansEffect
}

class PlansViewModel(
    private val repository: LoadingRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        app.user.let { user ->
            PlansState(
                allowed = user?.canSeePlans() == true,
                isDriver = user?.isDriver() == true,
                canPlan = user?.canPlanLoading() == true,
                seesChanges = user?.seesPlanChanges() == true,
            )
        },
    )
    val state: StateFlow<PlansState> = _state.asStateFlow()

    private val _effects = Channel<PlansEffect>(Channel.BUFFERED)
    val effects: Flow<PlansEffect> = _effects.receiveAsFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: PlansEvent) {
        if (!_state.value.allowed) return
        when (event) {
            is PlansEvent.StatusChosen -> {
                if (event.status == _state.value.status) return
                _state.update { it.copy(status = event.status, plans = emptyList()) }
                load()
            }
            PlansEvent.Retry -> load()
            PlansEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
            is PlansEvent.Open -> {
                val plan = event.plan
                _effects.trySend(PlansEffect.Open(planRouteFor(app.user, plan.id, plan.status, plan.sent)))
            }
            PlansEvent.NewPlan -> if (_state.value.canPlan) _effects.trySend(PlansEffect.NewPlan)
        }
    }

    private fun load(showSpinner: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.plans(_state.value.status)
                loadedOnce = true
                _state.update { it.copy(loading = false, plans = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
