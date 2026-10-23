package com.salesapp.ui.loading

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.LoadingRepository
import com.salesapp.data.canPlanLoading
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NewPlanState(
    val allowed: Boolean = true,
    // Working out which warehouses this user plans from.
    val loading: Boolean = true,
    // Facility code to the names of its companies, e.g. "F1" to "Lucky Ventures, Zoona Trading".
    val facilities: List<Pair<String, String>> = emptyList(),
    val chosen: String? = null,
    val creating: Boolean = false,
    val error: String? = null,
) {
    // With one warehouse the plan is made straight away, so there is nothing to choose.
    val choosing: Boolean get() = facilities.size > 1
    val noFacility: Boolean get() = !loading && error == null && facilities.isEmpty()
}

sealed interface NewPlanEvent {
    data class FacilityChosen(val code: String) : NewPlanEvent
    data object Make : NewPlanEvent
    data object Retry : NewPlanEvent
}

sealed interface NewPlanEffect {
    data class Created(val id: Int) : NewPlanEffect
}

class NewPlanViewModel(
    private val repository: LoadingRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(NewPlanState(allowed = app.user?.canPlanLoading() == true))
    val state: StateFlow<NewPlanState> = _state.asStateFlow()

    private val _effects = Channel<NewPlanEffect>(Channel.BUFFERED)
    val effects: Flow<NewPlanEffect> = _effects.receiveAsFlow()

    init {
        if (_state.value.allowed) loadFacilities() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: NewPlanEvent) {
        val now = _state.value
        if (!now.allowed) return
        when (event) {
            is NewPlanEvent.FacilityChosen -> if (!now.creating) _state.update { it.copy(chosen = event.code, error = null) }
            NewPlanEvent.Make -> now.chosen?.let { create(it) }
            NewPlanEvent.Retry -> when {
                now.facilities.isEmpty() -> loadFacilities()
                now.facilities.size == 1 -> create(now.facilities.first().first)
                else -> now.chosen?.let { create(it) }
            }
        }
    }

    private fun loadFacilities() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val list = repository.myFacilities()
                _state.update {
                    it.copy(loading = false, facilities = list, chosen = list.singleOrNull()?.first)
                }
                list.singleOrNull()?.let { create(it.first) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    private fun create(code: String) {
        if (_state.value.creating) return
        _state.update { it.copy(creating = true, error = null) }
        viewModelScope.launch {
            try {
                val plan = repository.create(code)
                _effects.send(NewPlanEffect.Created(plan.id))
            } catch (e: CancellationException) {
                _state.update { it.copy(creating = false) }
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(creating = false, error = message) }
            }
        }
    }
}
