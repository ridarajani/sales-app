package com.salesapp.ui.cash

import androidx.compose.ui.graphics.ImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.decodeImage
import com.salesapp.data.AppState
import com.salesapp.data.CashRepository
import com.salesapp.data.PaymentDetail
import com.salesapp.data.PaymentListItem
import com.salesapp.data.ServerFile
import com.salesapp.data.canSeePayments
import com.salesapp.resources.Res
import com.salesapp.resources.cancel_done
import com.salesapp.resources.cancel_need_reason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class PaymentsState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val query: String = "",
    // null = every way of paying; otherwise cash, bank or airtel.
    val mode: String? = null,
    val payments: List<PaymentListItem> = emptyList(),
    val error: String? = null,
) {
    val filtered: Boolean get() = query.isNotBlank() || mode != null
}

sealed interface PaymentsEvent {
    data class QueryChanged(val value: String) : PaymentsEvent
    data class ModeChosen(val mode: String?) : PaymentsEvent
    data object Retry : PaymentsEvent
    data object Refresh : PaymentsEvent
}

class PaymentsViewModel(
    private val repository: CashRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(PaymentsState(allowed = app.user?.canSeePayments() == true))
    val state: StateFlow<PaymentsState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: PaymentsEvent) {
        if (!_state.value.allowed) return
        when (event) {
            is PaymentsEvent.QueryChanged -> {
                if (event.value == _state.value.query) return
                _state.update { it.copy(query = event.value) }
                load(debounce = true)
            }
            is PaymentsEvent.ModeChosen -> {
                if (event.mode == _state.value.mode) return
                _state.update { it.copy(mode = event.mode, payments = emptyList()) }
                load()
            }
            PaymentsEvent.Retry -> load()
            PaymentsEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
        }
    }

    private fun load(showSpinner: Boolean = true, debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounce) delay(300)
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val s = _state.value
                val rows = repository.payments(s.query, s.mode)
                loadedOnce = true
                _state.update { it.copy(loading = false, payments = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}

data class PaymentDetailState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val payment: PaymentDetail? = null,
    val proof: ImageBitmap? = null,
    val signature: ImageBitmap? = null,
    val imagesFailed: Boolean = false,
    val error: String? = null,
    // Phase 8: cancelling a payment recorded by mistake.
    val cancelling: Boolean = false,
    val cancelReason: String = "",
    val busy: Boolean = false,
    val actionError: String? = null,
)

sealed interface PaymentDetailEvent {
    data object Retry : PaymentDetailEvent
    data object StartCancel : PaymentDetailEvent
    data object StopCancel : PaymentDetailEvent
    data class CancelReasonChanged(val value: String) : PaymentDetailEvent
    data object Cancel : PaymentDetailEvent
}

class PaymentDetailViewModel(
    private val id: Int,
    private val repository: CashRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(PaymentDetailState(allowed = app.user?.canSeePayments() == true))
    val state: StateFlow<PaymentDetailState> = _state.asStateFlow()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: PaymentDetailEvent) {
        val s = _state.value
        if (s.busy && event != PaymentDetailEvent.Retry) return
        when (event) {
            PaymentDetailEvent.Retry -> load()
            PaymentDetailEvent.StartCancel -> _state.update { it.copy(cancelling = true, cancelReason = "", actionError = null) }
            PaymentDetailEvent.StopCancel -> _state.update { it.copy(cancelling = false, cancelReason = "", actionError = null) }
            is PaymentDetailEvent.CancelReasonChanged -> _state.update { it.copy(cancelReason = event.value, actionError = null) }
            PaymentDetailEvent.Cancel -> cancel()
        }
    }

    // The payment stays on record but stops counting. The server refuses once the cash is with the
    // cashier or the Airtel payment is ticked, and says so in its own words.
    private fun cancel() {
        val s = _state.value
        if (s.busy || s.payment?.canCancel != true) return
        viewModelScope.launch {
            val reason = s.cancelReason.trim()
            if (reason.isEmpty()) {
                _state.update { it.copy(actionError = getString(Res.string.cancel_need_reason)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val saved = repository.cancel(s.payment.id, reason)
                app.message = getString(Res.string.cancel_done)
                _state.update { it.copy(busy = false, payment = saved, cancelling = false, cancelReason = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val payment = repository.payment(id)
                _state.update { it.copy(loading = false, payment = payment, error = null) }
                if (payment.hasProof) picture({ repository.proof(id) }) { image -> _state.update { it.copy(proof = image) } }
                if (payment.hasSignature) picture({ repository.signature(id) }) { image -> _state.update { it.copy(signature = image) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    private suspend fun picture(fetch: suspend () -> ServerFile, onLoaded: (ImageBitmap) -> Unit) {
        try {
            val image = decodeImage(fetch().bytes)
            if (image == null) _state.update { it.copy(imagesFailed = true) } else onLoaded(image)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // The payment still shows; only the picture is missing.
            _state.update { it.copy(imagesFailed = true) }
        }
    }
}
