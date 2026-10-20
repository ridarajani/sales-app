package com.salesapp.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.Customer
import com.salesapp.data.CustomerRequest
import com.salesapp.data.ListsRepository
import com.salesapp.data.OpeningBalance
import com.salesapp.data.User
import com.salesapp.data.canSeeOpeningBalances
import com.salesapp.resources.Res
import com.salesapp.resources.customer_switched_off_done
import com.salesapp.resources.customer_switched_on_done
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

data class CustomerDetailState(
    val loading: Boolean = true,
    val customer: Customer? = null,
    // Opening balances of this customer; only loaded for people who may see them.
    val balances: List<OpeningBalance> = emptyList(),
    val user: User? = null,
    val error: String? = null,
    val confirmSwitch: Boolean = false,
    val busy: Boolean = false,
    // A problem from switching on or off, shown on the screen.
    val actionError: String? = null,
)

sealed interface CustomerDetailEvent {
    data object Retry : CustomerDetailEvent
    data object Refresh : CustomerDetailEvent
    data object Edit : CustomerDetailEvent
    data class EnterBalance(val companyCode: String) : CustomerDetailEvent
    data object SwitchTapped : CustomerDetailEvent
    data object SwitchConfirmed : CustomerDetailEvent
    data object SwitchDismissed : CustomerDetailEvent
}

sealed interface CustomerDetailEffect {
    data class OpenForm(val id: Int) : CustomerDetailEffect
    data class OpenBalance(val customerId: Int, val companyCode: String) : CustomerDetailEffect
}

class CustomerDetailViewModel(
    private val id: Int,
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(CustomerDetailState(user = app.session.user.value))
    val state: StateFlow<CustomerDetailState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CustomerDetailEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<CustomerDetailEffect> = _effects.asSharedFlow()

    init {
        load(showSpinner = true)
    }

    fun onEvent(event: CustomerDetailEvent) {
        when (event) {
            CustomerDetailEvent.Retry -> load(showSpinner = true)
            CustomerDetailEvent.Refresh -> if (_state.value.customer != null) load(showSpinner = false)
            CustomerDetailEvent.Edit -> _effects.tryEmit(CustomerDetailEffect.OpenForm(id))
            is CustomerDetailEvent.EnterBalance -> _effects.tryEmit(CustomerDetailEffect.OpenBalance(id, event.companyCode))
            CustomerDetailEvent.SwitchTapped -> _state.update { it.copy(confirmSwitch = true) }
            CustomerDetailEvent.SwitchDismissed -> _state.update { it.copy(confirmSwitch = false) }
            CustomerDetailEvent.SwitchConfirmed -> switchActive()
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val customer = repository.customer(id)
                val user = app.session.user.value
                // The balances list is for everybody's customers; keep only this one.
                val balances = if (user?.canSeeOpeningBalances() == true) {
                    repository.openingBalances().filter { it.customerId == id }
                } else {
                    emptyList()
                }
                _state.update { it.copy(loading = false, customer = customer, balances = balances, user = user, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }

    private fun switchActive() {
        val customer = _state.value.customer ?: return
        if (_state.value.busy) return
        _state.update { it.copy(confirmSwitch = false, busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val updated = repository.updateCustomer(id, CustomerRequest(active = !customer.active))
                _state.update { it.copy(customer = updated, busy = false) }
                app.message = getString(if (updated.active) Res.string.customer_switched_on_done else Res.string.customer_switched_off_done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, actionError = app.handleApiError(e)) }
            }
        }
    }
}
