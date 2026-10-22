package com.salesapp.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.ShortDatedBatch
import com.salesapp.data.canSeeStock
import com.salesapp.data.StockRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ExpiringState(
    val loading: Boolean = true,
    val batches: List<ShortDatedBatch> = emptyList(),
    val error: String? = null,
    val allowed: Boolean = true,
) {
    // One group per company, in the order companies first appear; soonest expiry first inside each.
    val groups: List<Pair<String, List<ShortDatedBatch>>>
        get() = batches.groupBy { it.companyCode }.map { (code, rows) -> code to rows.sortedBy { it.expiryDate } }
}

sealed interface ExpiringEvent {
    data object Retry : ExpiringEvent
    data object Refresh : ExpiringEvent
    data class OpenBatch(val batchId: Int) : ExpiringEvent
}

sealed interface ExpiringEffect {
    data class OpenBatch(val batchId: Int) : ExpiringEffect
}

class ExpiringViewModel(
    private val repository: StockRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(ExpiringState(allowed = app.user?.canSeeStock() == true))
    val state: StateFlow<ExpiringState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ExpiringEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<ExpiringEffect> = _effects.asSharedFlow()

    init {
        if (_state.value.allowed) load(showSpinner = true) else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: ExpiringEvent) {
        if (!_state.value.allowed) return
        when (event) {
            ExpiringEvent.Retry -> load(showSpinner = true)
            ExpiringEvent.Refresh -> if (!_state.value.loading && _state.value.error == null) load(showSpinner = false)
            is ExpiringEvent.OpenBatch -> _effects.tryEmit(ExpiringEffect.OpenBatch(event.batchId))
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.shortDated(days = NEAR_EXPIRY_DAYS)
                _state.update { it.copy(loading = false, batches = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
