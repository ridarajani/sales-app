package com.salesapp.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.OrderListItem
import com.salesapp.data.OrdersRepository
import com.salesapp.data.canBookOrders
import com.salesapp.data.canSeeOrders
import com.salesapp.data.seesOrderStock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class OrdersState(
    val allowed: Boolean = true,
    // Salesmen never see stock, so no Short pill for them.
    val seesStock: Boolean = true,
    val canBook: Boolean = false,
    // null = all orders; otherwise open, hold, done or cancelled.
    val status: String? = null,
    val query: String = "",
    val loading: Boolean = true,
    val orders: List<OrderListItem> = emptyList(),
    val error: String? = null,
) {
    val filtering: Boolean get() = query.isNotBlank() || status != null
}

sealed interface OrdersEvent {
    data class QueryChanged(val query: String) : OrdersEvent
    data class StatusChosen(val status: String?) : OrdersEvent
    data object Retry : OrdersEvent
    data object Refresh : OrdersEvent
    data class OpenOrder(val id: Int) : OrdersEvent
    data object NewOrder : OrdersEvent
}

sealed interface OrdersEffect {
    data class OpenOrder(val id: Int) : OrdersEffect
    data object NewOrder : OrdersEffect
}

class OrdersViewModel(
    private val repository: OrdersRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        app.user.let { user ->
            OrdersState(
                allowed = user?.canSeeOrders() == true,
                seesStock = user?.seesOrderStock() == true,
                canBook = user?.canBookOrders() == true,
            )
        },
    )
    val state: StateFlow<OrdersState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<OrdersEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<OrdersEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: OrdersEvent) {
        if (!_state.value.allowed) return
        when (event) {
            is OrdersEvent.QueryChanged -> {
                _state.update { it.copy(query = event.query) }
                load(delayMillis = 300, showSpinner = false)
            }
            is OrdersEvent.StatusChosen -> {
                if (event.status == _state.value.status) return
                _state.update { it.copy(status = event.status) }
                load()
            }
            OrdersEvent.Retry -> load()
            OrdersEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
            is OrdersEvent.OpenOrder -> _effects.tryEmit(OrdersEffect.OpenOrder(event.id))
            OrdersEvent.NewOrder -> if (_state.value.canBook) _effects.tryEmit(OrdersEffect.NewOrder)
        }
    }

    private fun load(delayMillis: Long = 0, showSpinner: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            val now = _state.value
            try {
                val rows = repository.orders(now.status, now.query.trim().ifEmpty { null })
                loadedOnce = true
                _state.update { it.copy(loading = false, orders = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
