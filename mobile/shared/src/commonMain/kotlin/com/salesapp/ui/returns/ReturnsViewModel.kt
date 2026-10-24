package com.salesapp.ui.returns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.ReturnDetail
import com.salesapp.data.ReturnListItem
import com.salesapp.data.ReturnsRepository
import com.salesapp.data.canReceiveReturns
import com.salesapp.data.canSeeReturns
import com.salesapp.resources.Res
import com.salesapp.resources.return_received_done
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class ReturnsState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    // to_book, waiting, done or all.
    val status: String = "all",
    val query: String = "",
    val returns: List<ReturnListItem> = emptyList(),
    val error: String? = null,
    // The warehouse never sees what the goods cost.
    val showsMoney: Boolean = true,
) {
    val filtered: Boolean get() = status != "all" || query.isNotBlank()
}

sealed interface ReturnsEvent {
    data class StatusChosen(val status: String) : ReturnsEvent
    data class QueryChanged(val value: String) : ReturnsEvent
    data object Retry : ReturnsEvent
    data object Refresh : ReturnsEvent
}

class ReturnsViewModel(
    private val repository: ReturnsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ReturnsState(allowed = app.user?.canSeeReturns() == true, showsMoney = app.user?.role != "warehouse"),
    )
    val state: StateFlow<ReturnsState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: ReturnsEvent) {
        if (!_state.value.allowed) return
        when (event) {
            is ReturnsEvent.StatusChosen -> {
                if (event.status == _state.value.status) return
                _state.update { it.copy(status = event.status, returns = emptyList()) }
                load()
            }
            is ReturnsEvent.QueryChanged -> {
                if (event.value == _state.value.query) return
                _state.update { it.copy(query = event.value) }
                load(debounce = true)
            }
            ReturnsEvent.Retry -> load()
            ReturnsEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
        }
    }

    private fun load(showSpinner: Boolean = true, debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounce) delay(300)
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val s = _state.value
                val rows = repository.list(s.status, s.query)
                loadedOnce = true
                _state.update { it.copy(loading = false, returns = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}

data class ReturnDetailState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val salesReturn: ReturnDetail? = null,
    // Set while the warehouse is marking what came back damaged.
    val receiving: Boolean = false,
    val damaged: Set<Int> = emptySet(),
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
)

sealed interface ReturnDetailEvent {
    data object Retry : ReturnDetailEvent
    data object Refresh : ReturnDetailEvent
    data object Book : ReturnDetailEvent
    data object StartReceiving : ReturnDetailEvent
    data object StopReceiving : ReturnDetailEvent
    data class ConditionChosen(val lineId: Int, val damaged: Boolean) : ReturnDetailEvent
    data object Receive : ReturnDetailEvent
}

class ReturnDetailViewModel(
    private val id: Int,
    private val repository: ReturnsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(ReturnDetailState(allowed = app.user?.canSeeReturns() == true))
    val state: StateFlow<ReturnDetailState> = _state.asStateFlow()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: ReturnDetailEvent) {
        val s = _state.value
        if (!s.allowed || s.busy && event != ReturnDetailEvent.Retry) return
        when (event) {
            ReturnDetailEvent.Retry -> load()
            ReturnDetailEvent.Refresh -> if (s.salesReturn != null && !s.loading) load(showSpinner = false)
            ReturnDetailEvent.Book -> act { repository.book(id) }
            // Everything starts as good; the warehouse ticks only what is damaged.
            ReturnDetailEvent.StartReceiving -> _state.update { it.copy(receiving = true, damaged = emptySet(), actionError = null) }
            ReturnDetailEvent.StopReceiving -> _state.update { it.copy(receiving = false, damaged = emptySet(), actionError = null) }
            is ReturnDetailEvent.ConditionChosen -> _state.update {
                it.copy(damaged = if (event.damaged) it.damaged + event.lineId else it.damaged - event.lineId)
            }
            ReturnDetailEvent.Receive -> receive()
        }
    }

    private fun receive() {
        val s = _state.value
        if (s.busy || s.salesReturn?.canReceive != true) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val saved = repository.receive(id, s.damaged.sorted())
                app.message = getString(Res.string.return_received_done)
                _state.update { it.copy(busy = false, salesReturn = saved, receiving = false, damaged = emptySet()) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun act(call: suspend () -> ReturnDetail) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val saved = call()
                _state.update { it.copy(busy = false, salesReturn = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load(showSpinner: Boolean = true) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val saved = repository.detail(id)
                _state.update { it.copy(loading = false, salesReturn = saved, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { if (it.salesReturn == null) it.copy(loading = false, error = message) else it.copy(loading = false) }
            }
        }
    }
}
