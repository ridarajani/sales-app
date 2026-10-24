package com.salesapp.ui.cash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.CashCountRequest
import com.salesapp.data.CashRepository
import com.salesapp.data.HandoverDetail
import com.salesapp.data.HandoverListItem
import com.salesapp.data.canCountCash
import com.salesapp.resources.Res
import com.salesapp.resources.handover_done
import com.salesapp.resources.handover_need_count
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class CountCashState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val waiting: List<HandoverListItem> = emptyList(),
    val counted: List<HandoverListItem> = emptyList(),
    val error: String? = null,
)

sealed interface CountCashEvent {
    data object Retry : CountCashEvent
    data object Refresh : CountCashEvent
}

class CountCashViewModel(
    private val repository: CashRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(CountCashState(allowed = app.user?.canCountCash() == true))
    val state: StateFlow<CountCashState> = _state.asStateFlow()

    private var loadedOnce = false

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: CountCashEvent) {
        if (!_state.value.allowed) return
        when (event) {
            CountCashEvent.Retry -> load()
            CountCashEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
        }
    }

    private fun load(showSpinner: Boolean = true) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val all = repository.handovers(null)
                loadedOnce = true
                _state.update {
                    it.copy(
                        loading = false,
                        waiting = all.filter { h -> h.waiting },
                        counted = all.filter { h -> !h.waiting },
                        error = null,
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

data class HandoverDetailState(
    val loading: Boolean = true,
    val handover: HandoverDetail? = null,
    val counted: Long? = null,
    val note: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
) {
    // What the count is out by, once something is typed.
    val difference: Long? get() = counted?.let { it - (handover?.net ?: 0.0).toLong() }
}

sealed interface HandoverDetailEvent {
    data class CountedChanged(val value: Long?) : HandoverDetailEvent
    data class NoteChanged(val value: String) : HandoverDetailEvent
    data object Count : HandoverDetailEvent
    data object Retry : HandoverDetailEvent
}

class HandoverDetailViewModel(
    private val id: Int,
    private val repository: CashRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(HandoverDetailState())
    val state: StateFlow<HandoverDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: HandoverDetailEvent) {
        val s = _state.value
        if (s.busy && event != HandoverDetailEvent.Retry) return
        when (event) {
            is HandoverDetailEvent.CountedChanged -> _state.update { it.copy(counted = event.value, actionError = null) }
            is HandoverDetailEvent.NoteChanged -> _state.update { it.copy(note = event.value, actionError = null) }
            HandoverDetailEvent.Count -> count()
            HandoverDetailEvent.Retry -> load()
        }
    }

    private fun count() {
        val s = _state.value
        if (s.busy || s.handover?.canCount != true) return
        viewModelScope.launch {
            val counted = s.counted
            if (counted == null) {
                _state.update { it.copy(actionError = getString(Res.string.handover_need_count)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val saved = repository.count(id, CashCountRequest(counted = counted.toInt(), note = s.note.trim()))
                app.message = getString(Res.string.handover_done)
                _state.update { it.copy(busy = false, handover = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server says how much short or over it is and asks for a note.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val handover = repository.handover(id)
                // The field stays empty on purpose: a prefilled total can be confirmed with one tap
                // without anyone counting it. What the app expects is shown above the field instead.
                _state.update { it.copy(loading = false, handover = handover, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
