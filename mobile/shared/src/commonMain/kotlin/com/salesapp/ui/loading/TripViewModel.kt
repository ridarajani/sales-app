package com.salesapp.ui.loading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.LoadingRepository
import com.salesapp.data.PlanDetail
import com.salesapp.data.User
import com.salesapp.data.canSeePlans
import com.salesapp.data.isDriver
import com.salesapp.data.seesPlanChanges
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TripState(
    val loading: Boolean = true,
    val plan: PlanDetail? = null,
    val user: User? = null,
    val error: String? = null,
    val allowed: Boolean = true,
    // "OK, seen" is being sent.
    val busy: Boolean = false,
    // A problem from "OK, seen", shown under the changes.
    val actionError: String? = null,
) {
    // Only planners and the warehouse see the changes and may mark them seen; the flag is shared,
    // so drivers, accounts and cashier never touch it.
    val seesChanges: Boolean get() = user?.seesPlanChanges() == true

    // Drivers only see the drops; everyone else also gets the product totals.
    val showsProducts: Boolean get() = user?.isDriver() != true
}

sealed interface TripEvent {
    data object Retry : TripEvent
    data object Refresh : TripEvent
    data object Seen : TripEvent
}

class TripViewModel(
    private val id: Int,
    private val repository: LoadingRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(TripState(user = app.user, allowed = app.user?.canSeePlans() == true))
    val state: StateFlow<TripState> = _state.asStateFlow()

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
