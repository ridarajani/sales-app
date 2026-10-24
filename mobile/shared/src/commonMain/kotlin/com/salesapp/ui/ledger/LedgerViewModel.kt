package com.salesapp.ui.ledger

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Documents
import com.salesapp.core.epochDayFromIso
import com.salesapp.core.isoFromEpochDay
import com.salesapp.core.todayIsoDate
import com.salesapp.data.AppState
import com.salesapp.data.Company
import com.salesapp.data.Customer
import com.salesapp.data.Ledger
import com.salesapp.data.LedgerSource
import com.salesapp.data.canSeeLedger
import com.salesapp.resources.Res
import com.salesapp.resources.invoice_no_viewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// Which slice of the ledger is shown.
enum class LedgerPeriod { All, ThisMonth, Last30 }

data class LedgerState(
    val allowed: Boolean = true,
    // Step 1: pick the shop.
    val query: String = "",
    val customers: List<Customer> = emptyList(),
    val searching: Boolean = false,
    // Step 2: the ledger itself.
    val customer: Customer? = null,
    val companies: List<Company> = emptyList(),
    val companyCode: String? = null,
    val period: LedgerPeriod = LedgerPeriod.All,
    val loading: Boolean = false,
    val ledger: Ledger? = null,
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
)

sealed interface LedgerEvent {
    data class QueryChanged(val value: String) : LedgerEvent
    data class CustomerChosen(val id: Int) : LedgerEvent
    data object ChangeCustomer : LedgerEvent
    data class CompanyChosen(val code: String) : LedgerEvent
    data class PeriodChosen(val period: LedgerPeriod) : LedgerEvent
    data object Share : LedgerEvent
    data object Retry : LedgerEvent
}

class LedgerViewModel(
    customerId: Int?,
    companyCode: String?,
    private val repository: LedgerSource,
    private val documents: Documents,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        LedgerState(allowed = app.user?.canSeeLedger() == true, companyCode = companyCode),
    )
    val state: StateFlow<LedgerState> = _state.asStateFlow()

    private var searchJob: Job? = null

    // The ledger being fetched. Switching company, period or shop cancels the one before, so a
    // slower earlier answer can never land on top of a newer one.
    private var loadJob: Job? = null

    init {
        if (!_state.value.allowed) {
            _state.update { it.copy(loading = false) }
        } else if (customerId != null) {
            // Opened from an invoice or a customer: that shop is already picked.
            pick(customerId, keepCompany = companyCode)
        } else {
            search()
        }
    }

    fun onEvent(event: LedgerEvent) {
        val s = _state.value
        if (!s.allowed || s.busy) return
        when (event) {
            is LedgerEvent.QueryChanged -> {
                if (event.value == s.query) return
                _state.update { it.copy(query = event.value) }
                search(debounce = true)
            }
            is LedgerEvent.CustomerChosen -> pick(event.id)
            LedgerEvent.ChangeCustomer -> {
                loadJob?.cancel()
                _state.update { LedgerState(allowed = true, query = it.query, customers = it.customers) }
                search()
            }
            is LedgerEvent.CompanyChosen -> {
                if (event.code == s.companyCode) return
                _state.update { it.copy(companyCode = event.code, ledger = null) }
                load()
            }
            is LedgerEvent.PeriodChosen -> {
                if (event.period == s.period) return
                _state.update { it.copy(period = event.period, ledger = null) }
                load()
            }
            LedgerEvent.Share -> share()
            LedgerEvent.Retry -> if (s.customer != null) load() else search()
        }
    }

    // The first day of the period, or null for everything.
    private fun fromDate(period: LedgerPeriod): String? = when (period) {
        LedgerPeriod.All -> null
        LedgerPeriod.ThisMonth -> todayIsoDate().take(7) + "-01"
        LedgerPeriod.Last30 -> isoFromEpochDay(epochDayToday() - 29)
    }

    private fun epochDayToday(): Long = epochDayFromIso(todayIsoDate()) ?: 0L

    private fun search(debounce: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(300)
            _state.update { it.copy(searching = true, error = null) }
            try {
                val rows = repository.customers(_state.value.query)
                _state.update { it.copy(searching = false, customers = rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(searching = false, error = message) }
            }
        }
    }

    private fun pick(customerId: Int, keepCompany: String? = null) {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                val customer = repository.customer(customerId)
                val companies = repository.companiesOf(customer)
                val code = keepCompany?.takeIf { c -> companies.any { it.code == c } } ?: companies.firstOrNull()?.code
                _state.update { it.copy(customer = customer, companies = companies, companyCode = code) }
                if (code == null) {
                    _state.update { it.copy(loading = false) }
                    return@launch
                }
                // Part of the same job, so changing shop cancels the ledger it had started fetching.
                fetch(customer.id, code, _state.value.period)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    private fun load() {
        val s = _state.value
        val customer = s.customer ?: return
        val company = s.companyCode ?: return
        loadJob?.cancel()
        _state.update { it.copy(loading = true, error = null) }
        loadJob = viewModelScope.launch {
            try {
                fetch(customer.id, company, s.period)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }

    // The one place the ledger is fetched. The caller owns the job, so cancelling it stops the
    // answer being applied.
    private suspend fun fetch(customerId: Int, company: String, period: LedgerPeriod) {
        val ledger = repository.ledger(customerId, company, fromDate(period))
        _state.update { it.copy(loading = false, ledger = ledger, error = null) }
    }

    // The statement for the company and period on screen, handed to the phone's share sheet.
    private fun share() {
        val s = _state.value
        val customer = s.customer ?: return
        val company = s.companyCode ?: return
        if (s.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val file = repository.statement(customer.id, company, fromDate(s.period))
                val name = "statement-$company-${customer.id}.pdf"
                val opened = documents.open(name, file.bytes, file.contentType, share = true)
                _state.update { it.copy(busy = false, actionError = if (opened) null else getString(Res.string.invoice_no_viewer)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }
}
