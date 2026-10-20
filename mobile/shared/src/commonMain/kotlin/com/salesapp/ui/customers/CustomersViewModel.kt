package com.salesapp.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.Customer
import com.salesapp.data.ListsRepository
import com.salesapp.data.User
import com.salesapp.data.canAddCustomers
import com.salesapp.data.companyCodes
import com.salesapp.data.Role
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

data class CustomersState(
    val query: String = "",
    // The company chosen in the filter chips; null means all.
    val company: String? = null,
    val includeInactive: Boolean = false,
    val loading: Boolean = true,
    val customers: List<Customer> = emptyList(),
    val error: String? = null,
    // The companies the user can pick from; the chips only show when there is more than one.
    val companies: List<String> = emptyList(),
    val canAdd: Boolean = false,
)

sealed interface CustomersEvent {
    data class QueryChanged(val query: String) : CustomersEvent
    data class CompanyChosen(val code: String?) : CustomersEvent
    data object ToggleInactive : CustomersEvent
    data object Retry : CustomersEvent
    // The screen came back into view, e.g. after editing a customer.
    data object Refresh : CustomersEvent
    data class OpenCustomer(val id: Int) : CustomersEvent
    data object AddCustomer : CustomersEvent
}

sealed interface CustomersEffect {
    data class OpenCustomer(val id: Int) : CustomersEffect
    data object AddCustomer : CustomersEffect
}

class CustomersViewModel(
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(CustomersState(companies = companiesOf(app.session.user.value), canAdd = app.session.user.value?.canAddCustomers() == true))
    val state: StateFlow<CustomersState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CustomersEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<CustomersEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        load()
    }

    fun onEvent(event: CustomersEvent) {
        when (event) {
            is CustomersEvent.QueryChanged -> {
                _state.update { it.copy(query = event.query) }
                // Wait for a pause in typing before asking the server.
                load(delayMillis = SEARCH_DELAY_MILLIS)
            }
            is CustomersEvent.CompanyChosen -> {
                _state.update { it.copy(company = event.code) }
                load()
            }
            CustomersEvent.ToggleInactive -> {
                _state.update { it.copy(includeInactive = !it.includeInactive) }
                load()
            }
            CustomersEvent.Retry -> load()
            CustomersEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
            is CustomersEvent.OpenCustomer -> _effects.tryEmit(CustomersEffect.OpenCustomer(event.id))
            CustomersEvent.AddCustomer -> _effects.tryEmit(CustomersEffect.AddCustomer)
        }
    }

    private fun load(delayMillis: Long = 0, showSpinner: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            val now = _state.value
            try {
                val rows = repository.customers(now.query, now.company, now.includeInactive)
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

        fun companiesOf(user: User?): List<String> {
            if (user == null) return emptyList()
            val all = Role.fromCode(user.role)?.seesAllCompanies == true
            return if (all) companyCodes else companyCodes.filter { it in user.companies }
        }
    }
}
