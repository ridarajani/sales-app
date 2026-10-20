package com.salesapp.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.todayIsoDate
import com.salesapp.data.AppState
import com.salesapp.data.ListsRepository
import com.salesapp.data.OpeningBalance
import com.salesapp.data.OpeningBalanceRequest
import com.salesapp.data.User
import com.salesapp.resources.Res
import com.salesapp.resources.balance_err_amount
import com.salesapp.resources.balance_saved
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

data class OpeningBalanceState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val customerName: String = "",
    val companyCode: String = "",
    val user: User? = null,
    // The balance already entered for this customer and company, if any.
    val existing: OpeningBalance? = null,
    val amount: Long? = null,
    val asOf: String = "",
    val error: String? = null,
    val saving: Boolean = false,
)

sealed interface OpeningBalanceEvent {
    data object Retry : OpeningBalanceEvent
    data class AmountChanged(val value: Long?) : OpeningBalanceEvent
    data class DateChanged(val value: String) : OpeningBalanceEvent
    data object Save : OpeningBalanceEvent
}

sealed interface OpeningBalanceEffect {
    data object Done : OpeningBalanceEffect
}

class OpeningBalanceViewModel(
    private val customerId: Int,
    private val companyCode: String,
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        OpeningBalanceState(companyCode = companyCode, user = app.session.user.value, asOf = todayIsoDate()),
    )
    val state: StateFlow<OpeningBalanceState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<OpeningBalanceEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<OpeningBalanceEffect> = _effects.asSharedFlow()

    init {
        load()
    }

    fun onEvent(event: OpeningBalanceEvent) {
        when (event) {
            OpeningBalanceEvent.Retry -> load()
            is OpeningBalanceEvent.AmountChanged -> _state.update { it.copy(amount = event.value, error = null) }
            is OpeningBalanceEvent.DateChanged -> _state.update { it.copy(asOf = event.value, error = null) }
            OpeningBalanceEvent.Save -> save()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val customer = repository.customer(customerId)
                val existing = repository.openingBalances().firstOrNull { it.customerId == customerId && it.companyCode == companyCode }
                _state.update {
                    it.copy(
                        loading = false,
                        customerName = customer.name,
                        user = app.session.user.value,
                        existing = existing,
                        amount = existing?.amount ?: it.amount,
                        asOf = existing?.asOf?.take(10) ?: it.asOf,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = app.handleApiError(e)) }
            }
        }
    }

    private fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        val amount = s.amount
        if (amount == null) {
            viewModelScope.launch { _state.update { it.copy(error = getString(Res.string.balance_err_amount)) } }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                repository.setOpeningBalance(customerId, OpeningBalanceRequest(companyCode, amount, s.asOf))
                _state.update { it.copy(saving = false) }
                app.message = getString(Res.string.balance_saved)
                _effects.tryEmit(OpeningBalanceEffect.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = app.handleApiError(e)) }
            }
        }
    }
}
