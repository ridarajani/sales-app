package com.salesapp.ui.cash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.CashHeld
import com.salesapp.data.CashRepository
import com.salesapp.data.HandoverListItem
import com.salesapp.data.holdsCash
import com.salesapp.resources.Res
import com.salesapp.resources.cash_handed_over
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class MyCashState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val held: CashHeld? = null,
    val past: List<HandoverListItem> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
    // The handover just made, so its number can be shown.
    val handedOver: String? = null,
) {
    val hasSomething: Boolean get() = (held?.net ?: 0.0) != 0.0 ||
        held?.collections?.isNotEmpty() == true ||
        held?.withdrawals?.isNotEmpty() == true ||
        held?.expenses?.isNotEmpty() == true
}

sealed interface MyCashEvent {
    data object Retry : MyCashEvent
    data object Refresh : MyCashEvent
    data object HandOver : MyCashEvent
}

class MyCashViewModel(
    private val repository: CashRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(MyCashState(allowed = app.user?.holdsCash() == true))
    val state: StateFlow<MyCashState> = _state.asStateFlow()

    private var loadedOnce = false

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: MyCashEvent) {
        if (!_state.value.allowed) return
        when (event) {
            MyCashEvent.Retry -> load()
            MyCashEvent.Refresh -> if (loadedOnce && !_state.value.busy) load(showSpinner = false)
            MyCashEvent.HandOver -> handOver()
        }
    }

    private fun handOver() {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val handover = repository.handOver()
                app.message = getString(Res.string.cash_handed_over, handover.number)
                _state.update { it.copy(busy = false, handedOver = handover.number) }
                load(showSpinner = false)
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
                val held = repository.myCash()
                val past = runCatching { repository.handovers(null) }.getOrDefault(emptyList())
                loadedOnce = true
                _state.update { it.copy(loading = false, held = held, past = past, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { if (it.held == null) it.copy(loading = false, error = message) else it.copy(loading = false) }
            }
        }
    }
}
