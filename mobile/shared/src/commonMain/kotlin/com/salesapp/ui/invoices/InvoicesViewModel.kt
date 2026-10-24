package com.salesapp.ui.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.Company
import com.salesapp.data.InvoiceListItem
import com.salesapp.data.InvoiceRepository
import com.salesapp.data.User
import com.salesapp.data.canSeeInvoices
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class InvoicesState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val query: String = "",
    // null = every company; otherwise a company code.
    val company: String? = null,
    // null = both; otherwise vat or plain.
    val type: String? = null,
    val companies: List<Company> = emptyList(),
    val invoices: List<InvoiceListItem> = emptyList(),
    val error: String? = null,
    val user: User? = null,
) {
    val filtered: Boolean get() = query.isNotBlank() || company != null || type != null
}

sealed interface InvoicesEvent {
    data class QueryChanged(val value: String) : InvoicesEvent
    data class CompanyChosen(val code: String?) : InvoicesEvent
    data class TypeChosen(val type: String?) : InvoicesEvent
    data object Retry : InvoicesEvent
    data object Refresh : InvoicesEvent
}

class InvoicesViewModel(
    private val repository: InvoiceRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        InvoicesState(allowed = app.user?.canSeeInvoices() == true, user = app.user),
    )
    val state: StateFlow<InvoicesState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        if (_state.value.allowed) {
            load()
            loadCompanies()
        } else {
            _state.update { it.copy(loading = false) }
        }
    }

    fun onEvent(event: InvoicesEvent) {
        if (!_state.value.allowed) return
        when (event) {
            is InvoicesEvent.QueryChanged -> {
                if (event.value == _state.value.query) return
                _state.update { it.copy(query = event.value) }
                // Typing waits a moment so every letter is not a new request.
                load(debounce = true)
            }
            is InvoicesEvent.CompanyChosen -> {
                if (event.code == _state.value.company) return
                _state.update { it.copy(company = event.code, invoices = emptyList()) }
                load()
            }
            is InvoicesEvent.TypeChosen -> {
                if (event.type == _state.value.type) return
                _state.update { it.copy(type = event.type, invoices = emptyList()) }
                load()
            }
            InvoicesEvent.Retry -> load()
            InvoicesEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
        }
    }

    private fun loadCompanies() {
        viewModelScope.launch {
            try {
                val companies = repository.myCompanies()
                // Chips only help when there is more than one company to pick from.
                if (companies.size > 1) _state.update { it.copy(companies = companies) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // The list still works without the chips.
            }
        }
    }

    private fun load(showSpinner: Boolean = true, debounce: Boolean = false) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (debounce) delay(300)
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val s = _state.value
                val rows = repository.list(s.query, s.company, s.type)
                loadedOnce = true
                _state.update { it.copy(loading = false, invoices = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
