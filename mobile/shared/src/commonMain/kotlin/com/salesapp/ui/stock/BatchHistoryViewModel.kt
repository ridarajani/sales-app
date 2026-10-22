package com.salesapp.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.StockBatch
import com.salesapp.data.StockMovement
import com.salesapp.data.StockProduct
import com.salesapp.data.StockRepository
import com.salesapp.data.canKeepStock
import com.salesapp.data.canSeeStock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class BatchHistoryState(
    val loading: Boolean = true,
    val product: StockProduct? = null,
    val batch: StockBatch? = null,
    val movements: List<StockMovement> = emptyList(),
    val error: String? = null,
    val canKeep: Boolean = false,
    val allowed: Boolean = true,
)

sealed interface BatchHistoryEvent {
    data object Retry : BatchHistoryEvent
    data object Refresh : BatchHistoryEvent
    data object Adjust : BatchHistoryEvent
}

sealed interface BatchHistoryEffect {
    data class Adjust(val batchId: Int) : BatchHistoryEffect
}

class BatchHistoryViewModel(
    private val batchId: Int,
    private val repository: StockRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(BatchHistoryState(canKeep = app.session.user.value?.canKeepStock() == true, allowed = app.user?.canSeeStock() == true))
    val state: StateFlow<BatchHistoryState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<BatchHistoryEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<BatchHistoryEffect> = _effects.asSharedFlow()

    init {
        if (_state.value.allowed) load(showSpinner = true) else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: BatchHistoryEvent) {
        if (!_state.value.allowed) return
        when (event) {
            BatchHistoryEvent.Retry -> load(showSpinner = true)
            BatchHistoryEvent.Refresh -> if (!_state.value.loading && _state.value.error == null) load(showSpinner = false)
            BatchHistoryEvent.Adjust -> _effects.tryEmit(BatchHistoryEffect.Adjust(batchId))
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val found = repository.batch(batchId)
                val movements = repository.movements(batchId)
                _state.update {
                    it.copy(loading = false, product = found?.first, batch = found?.second, movements = movements, error = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
