package com.salesapp.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
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

data class StockState(
    val loading: Boolean = true,
    val products: List<StockProduct> = emptyList(),
    val query: String = "",
    val error: String? = null,
    val canKeep: Boolean = false,
    val allowed: Boolean = true,
) {
    // The loaded list narrowed by what was typed, in the order the server gave.
    val visible: List<StockProduct>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return products
            return products.filter {
                "${it.brand} ${it.name} ${it.packSize} ${it.code}".contains(q, ignoreCase = true)
            }
        }
}

sealed interface StockEvent {
    data object Retry : StockEvent
    data object Refresh : StockEvent
    data class QueryChanged(val query: String) : StockEvent
    data class OpenProduct(val productId: Int) : StockEvent
    data object Receive : StockEvent
    data object Count : StockEvent
}

sealed interface StockEffect {
    data class OpenProduct(val productId: Int) : StockEffect
    data object Receive : StockEffect
    data object Count : StockEffect
}

class StockViewModel(
    private val repository: StockRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(StockState(canKeep = app.session.user.value?.canKeepStock() == true, allowed = app.user?.canSeeStock() == true))
    val state: StateFlow<StockState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<StockEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<StockEffect> = _effects.asSharedFlow()

    init {
        if (_state.value.allowed) load(showSpinner = true) else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: StockEvent) {
        if (!_state.value.allowed) return
        when (event) {
            StockEvent.Retry -> load(showSpinner = true)
            StockEvent.Refresh -> if (!_state.value.loading && _state.value.error == null) load(showSpinner = false)
            is StockEvent.QueryChanged -> _state.update { it.copy(query = event.query) }
            is StockEvent.OpenProduct -> _effects.tryEmit(StockEffect.OpenProduct(event.productId))
            StockEvent.Receive -> _effects.tryEmit(StockEffect.Receive)
            StockEvent.Count -> _effects.tryEmit(StockEffect.Count)
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.stock()
                _state.update { it.copy(loading = false, products = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
