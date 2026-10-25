package com.salesapp.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AlertsRepository
import com.salesapp.data.AppState
import com.salesapp.data.OutboxProblem
import com.salesapp.data.canSeeUnreadSales
import com.salesapp.resources.Res
import com.salesapp.resources.problem_need_note
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// One file of trip sales a phone could not read back. Admin enters whatever is missing by hand on
// the office website, then says here what was done, which lets the trip be closed.
data class OutboxProblemState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val problem: OutboxProblem? = null,
    val note: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
)

sealed interface OutboxProblemEvent {
    data object Retry : OutboxProblemEvent
    data class NoteChanged(val value: String) : OutboxProblemEvent
    data object Clear : OutboxProblemEvent
}

class OutboxProblemViewModel(
    private val id: Int,
    private val repository: AlertsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(OutboxProblemState(allowed = app.user?.canSeeUnreadSales() == true))
    val state: StateFlow<OutboxProblemState> = _state.asStateFlow()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: OutboxProblemEvent) {
        if (!_state.value.allowed) return
        when (event) {
            OutboxProblemEvent.Retry -> load()
            is OutboxProblemEvent.NoteChanged -> _state.update { it.copy(note = event.value, actionError = null) }
            OutboxProblemEvent.Clear -> clear()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val problem = repository.problem(id)
                _state.update { it.copy(loading = false, problem = problem, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }

    private fun clear() {
        val s = _state.value
        if (s.busy || s.problem?.canClear != true) return
        viewModelScope.launch {
            val note = s.note.trim()
            if (note.isEmpty()) {
                _state.update { it.copy(actionError = getString(Res.string.problem_need_note)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val cleared = repository.clearProblem(id, note)
                _state.update { it.copy(busy = false, problem = cleared, note = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server's own words, such as somebody else having cleared it already.
                _state.update { it.copy(busy = false, actionError = app.handleApiError(e)) }
            }
        }
    }
}
