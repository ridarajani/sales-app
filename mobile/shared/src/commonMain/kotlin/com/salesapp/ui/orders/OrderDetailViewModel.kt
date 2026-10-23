package com.salesapp.ui.orders

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.OrderDetail
import com.salesapp.data.OrderHistoryEntry
import com.salesapp.data.OrdersRepository
import com.salesapp.data.User
import com.salesapp.data.canSeeOrders
import com.salesapp.data.seesOrderStock
import com.salesapp.resources.Res
import com.salesapp.resources.order_detail_cancelled
import com.salesapp.resources.order_detail_closed
import com.salesapp.resources.order_detail_held
import com.salesapp.resources.order_detail_released
import com.salesapp.resources.order_detail_reserved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// What can be done to an order from its page. Cancel and Close ask "are you sure?" first.
enum class OrderAction(val needsConfirm: Boolean) {
    Hold(false),
    Release(false),
    Reserve(false),
    Close(true),
    Cancel(true),
}

data class OrderDetailState(
    val loading: Boolean = true,
    val order: OrderDetail? = null,
    val user: User? = null,
    val error: String? = null,
    val allowed: Boolean = true,
    // An action is being sent; every action button is off.
    val busy: Boolean = false,
    // The action the person is being asked to confirm.
    val asking: OrderAction? = null,
    // A problem from an action, shown above the buttons.
    val actionError: String? = null,
) {
    // Salesmen never see stock: no Short pill, no "not in stock yet", no "Reserve again".
    val seesStock: Boolean get() = user?.seesOrderStock() == true

    // Oldest first, as the server sends it. For salesmen the stock entries are left out and the
    // server's stock sentences are taken out of the other entries, keeping the rest (such as a typed reason).
    val history: List<OrderHistoryEntry>
        get() = order?.history.orEmpty()
            .filter { seesStock || !it.title.startsWith("Stock reserved") }
            .map { entry -> if (seesStock) entry else entry.copy(detail = withoutStockWords(entry.detail)) }

    val canHold: Boolean get() = order?.let { it.canManage && it.status == "open" } == true
    val canRelease: Boolean get() = order?.let { it.canManage && it.status == "hold" } == true
    val canClose: Boolean get() = order?.let { it.canManage && (it.status == "open" || it.status == "hold") } == true
    val canChange: Boolean get() = order?.canChange == true
    val canReserve: Boolean get() = order?.let { it.shortPieces > 0 && it.canChange } == true && seesStock
}

// Sentences the server puts in history details that talk about stock (orders.ts hold/release/cancel/close).
private val STOCK_SENTENCES = listOf(
    "Not all of it is in stock.",
    "Stock stays reserved.",
    "Reserved stock released.",
    "Its reserved stock was released.",
)

private val SPACES = Regex(" {2,}")

internal fun withoutStockWords(detail: String): String =
    STOCK_SENTENCES.fold(detail) { d, sentence -> d.replace(sentence, "") }.replace(SPACES, " ").trim()

sealed interface OrderDetailEvent {
    data object Retry : OrderDetailEvent
    data object Refresh : OrderDetailEvent
    data object Change : OrderDetailEvent
    data class Act(val action: OrderAction) : OrderDetailEvent
    data object Confirm : OrderDetailEvent
    data object Dismiss : OrderDetailEvent
}

sealed interface OrderDetailEffect {
    data class OpenChange(val id: Int) : OrderDetailEffect
}

class OrderDetailViewModel(
    private val id: Int,
    private val repository: OrdersRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(OrderDetailState(user = app.user, allowed = app.user?.canSeeOrders() == true))
    val state: StateFlow<OrderDetailState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<OrderDetailEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<OrderDetailEffect> = _effects.asSharedFlow()

    init {
        if (_state.value.allowed) load(showSpinner = true) else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: OrderDetailEvent) {
        if (!_state.value.allowed) return
        when (event) {
            OrderDetailEvent.Retry -> load(showSpinner = true)
            OrderDetailEvent.Refresh -> {
                val s = _state.value
                if (s.order != null && !s.loading && !s.busy) load(showSpinner = false)
            }
            OrderDetailEvent.Change -> if (_state.value.canChange && !_state.value.busy) _effects.tryEmit(OrderDetailEffect.OpenChange(id))
            is OrderDetailEvent.Act -> {
                if (_state.value.busy) return
                if (event.action.needsConfirm) _state.update { it.copy(asking = event.action) } else run(event.action)
            }
            OrderDetailEvent.Confirm -> _state.value.asking?.let { action ->
                _state.update { it.copy(asking = null) }
                run(action)
            }
            OrderDetailEvent.Dismiss -> _state.update { it.copy(asking = null) }
        }
    }

    private fun run(action: OrderAction) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val order = when (action) {
                    OrderAction.Hold -> repository.hold(id)
                    OrderAction.Release -> repository.release(id)
                    OrderAction.Reserve -> repository.reserve(id)
                    OrderAction.Close -> repository.close(id)
                    OrderAction.Cancel -> repository.cancel(id)
                }
                _state.update { it.copy(order = order, busy = false) }
                app.message = getString(
                    when (action) {
                        OrderAction.Hold -> Res.string.order_detail_held
                        OrderAction.Release -> Res.string.order_detail_released
                        OrderAction.Reserve -> Res.string.order_detail_reserved
                        OrderAction.Close -> Res.string.order_detail_closed
                        OrderAction.Cancel -> Res.string.order_detail_cancelled
                    },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val order = repository.order(id)
                _state.update { it.copy(loading = false, order = order, error = null, user = app.user) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                // A quiet refresh that fails keeps the order already on screen.
                _state.update { if (it.order == null) it.copy(loading = false, error = message) else it.copy(loading = false) }
            }
        }
    }
}
