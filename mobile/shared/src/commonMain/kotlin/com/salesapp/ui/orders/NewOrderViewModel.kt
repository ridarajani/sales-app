package com.salesapp.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.Customer
import com.salesapp.data.OrdersRepository
import com.salesapp.data.canBookOrders
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

data class NewOrderState(
    val allowed: Boolean = true,
    val query: String = "",
    val loading: Boolean = true,
    val customers: List<Customer> = emptyList(),
    val error: String? = null,
)

sealed interface NewOrderEvent {
    data class QueryChanged(val query: String) : NewOrderEvent
    data class PickCustomer(val id: Int) : NewOrderEvent
    data object Retry : NewOrderEvent
    // The screen came back into view, e.g. after going back from the booking.
    data object Refresh : NewOrderEvent
}

sealed interface NewOrderEffect {
    data class PickCustomer(val id: Int) : NewOrderEffect
}

// Step 1 of booking an order: find the shop.
class NewOrderViewModel(
    private val repository: OrdersRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(NewOrderState(allowed = app.user?.canBookOrders() == true))
    val state: StateFlow<NewOrderState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<NewOrderEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<NewOrderEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: NewOrderEvent) {
        when (event) {
            is NewOrderEvent.QueryChanged -> {
                _state.update { it.copy(query = event.query) }
                // Wait for a pause in typing before asking the server.
                load(delayMillis = SEARCH_DELAY_MILLIS)
            }
            is NewOrderEvent.PickCustomer -> _effects.tryEmit(NewOrderEffect.PickCustomer(event.id))
            NewOrderEvent.Retry -> load()
            NewOrderEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
        }
    }

    private fun load(delayMillis: Long = 0, showSpinner: Boolean = true) {
        if (!_state.value.allowed) return
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.customers(_state.value.query.trim().ifEmpty { null })
                loadedOnce = true
                _state.update { it.copy(loading = false, customers = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }

    private companion object {
        const val SEARCH_DELAY_MILLIS = 300L
    }
}
