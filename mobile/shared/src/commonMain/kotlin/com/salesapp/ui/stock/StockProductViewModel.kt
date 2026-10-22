package com.salesapp.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.StockProduct
import com.salesapp.data.StockRepository
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

data class StockProductState(
    val loading: Boolean = true,
    val product: StockProduct? = null,
    val error: String? = null,
    val allowed: Boolean = true,
)

sealed interface StockProductEvent {
    data object Retry : StockProductEvent
    data object Refresh : StockProductEvent
    data class OpenBatch(val batchId: Int) : StockProductEvent
}

sealed interface StockProductEffect {
    data class OpenBatch(val batchId: Int) : StockProductEffect
}

class StockProductViewModel(
    private val productId: Int,
    private val repository: StockRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(StockProductState(
            product = repository.cachedProduct(productId),
            loading = repository.cachedProduct(productId) == null,
            allowed = app.user?.canSeeStock() == true,
        ))
    val state: StateFlow<StockProductState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StockProductEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<StockProductEffect> = _effects.asSharedFlow()

    init {
        if (_state.value.allowed) load(showSpinner = _state.value.product == null) else _state.update { it.copy(loading = false, product = null) }
    }

    fun onEvent(event: StockProductEvent) {
        if (!_state.value.allowed) return
        when (event) {
            StockProductEvent.Retry -> load(showSpinner = true)
            StockProductEvent.Refresh -> if (!_state.value.loading && _state.value.error == null) load(showSpinner = false)
            is StockProductEvent.OpenBatch -> _effects.tryEmit(StockProductEffect.OpenBatch(event.batchId))
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                // After a stock change the cache is empty, so this loads fresh; otherwise it is the list from a moment ago.
                val product = repository.product(productId)
                _state.update { it.copy(loading = false, product = product, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
