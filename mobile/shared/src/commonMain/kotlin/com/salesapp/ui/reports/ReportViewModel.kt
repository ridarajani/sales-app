package com.salesapp.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Documents
import com.salesapp.data.AppState
import com.salesapp.data.ListsRepository
import com.salesapp.data.ReportColumn
import com.salesapp.data.ReportFilters
import com.salesapp.data.ReportInfo
import com.salesapp.data.ReportPerson
import com.salesapp.data.ReportResult
import com.salesapp.data.ReportsRepository
import com.salesapp.resources.Res
import com.salesapp.resources.report_need_channel
import com.salesapp.resources.report_need_company
import com.salesapp.resources.report_need_customer
import com.salesapp.resources.report_need_dates
import com.salesapp.resources.report_need_person
import com.salesapp.resources.report_need_product
import com.salesapp.resources.report_no_viewer
import com.salesapp.resources.reports_no_match
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

// What is being picked on top of the report: a customer or a product, both searched on the server.
enum class ReportPick { Customer, Product }

// One thing to pick out of a searched list.
data class PickItem(val id: Int, val title: String, val note: String = "")

data class ReportState(
    val key: String = "",
    val loading: Boolean = true,
    val error: String? = null,
    val info: ReportInfo? = null,
    val people: List<ReportPerson> = emptyList(),
    // The companies this person works for, for the company filter.
    val companies: List<String> = emptyList(),
    val filters: ReportFilters = ReportFilters(),
    val running: Boolean = false,
    val result: ReportResult? = null,
    val actionError: String? = null,
    // "xlsx" or "pdf" while the file is being fetched.
    val sharing: String? = null,
    val picking: ReportPick? = null,
    val query: String = "",
    val found: List<PickItem> = emptyList(),
    val searching: Boolean = false,
) {
    val title: String get() = result?.title ?: info?.title.orEmpty()

    // Filters this report cannot run without, and that are not picked yet.
    val missing: List<String> get() = info?.required.orEmpty().filterNot { filters.has(it) }

    val columns: List<ReportColumn> get() = result?.columns.orEmpty()

    val rows: List<JsonObject> get() = result?.rows.orEmpty()

    // On a phone each row is a card: the first one or two words-columns are its heading, and the
    // rest become label and value lines.
    val headingColumns: List<ReportColumn> get() = columns.filter { it.isText }.take(2)

    val detailColumns: List<ReportColumn> get() = columns.filterNot { it in headingColumns }

    val totals: Map<String, Double> get() = result?.totals.orEmpty()

    val totalColumns: List<ReportColumn> get() = columns.filter { it.total && totals.containsKey(it.key) }

    val empty: Boolean get() = result != null && rows.isEmpty()

    val busy: Boolean get() = running || sharing != null
}

sealed interface ReportEvent {
    data object Retry : ReportEvent
    data object Show : ReportEvent
    data class FromChanged(val iso: String) : ReportEvent
    data class ToChanged(val iso: String) : ReportEvent
    // Null means every company this person works for.
    data class CompanyChosen(val code: String?) : ReportEvent
    data class PersonChosen(val id: Int?) : ReportEvent
    data class ChannelChosen(val code: String?) : ReportEvent
    data class OpenPicker(val pick: ReportPick) : ReportEvent
    data object ClosePicker : ReportEvent
    data class QueryChanged(val value: String) : ReportEvent
    data class CustomerChosen(val id: Int, val name: String) : ReportEvent
    data class ProductChosen(val id: Int, val name: String) : ReportEvent
    data object Excel : ReportEvent
    data object Pdf : ReportEvent
}

class ReportViewModel(
    private val key: String,
    private val repository: ReportsRepository,
    private val lists: ListsRepository,
    private val documents: Documents,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(ReportState(key = key))
    val state: StateFlow<ReportState> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var runJob: Job? = null

    init {
        load()
    }

    fun onEvent(event: ReportEvent) {
        // While it is working, only Retry gets through; the rest would race the answer.
        if (_state.value.busy && event != ReportEvent.Retry) return
        when (event) {
            ReportEvent.Retry -> load()
            ReportEvent.Show -> run()
            is ReportEvent.FromChanged -> change { it.copy(from = event.iso) }
            is ReportEvent.ToChanged -> change { it.copy(to = event.iso) }
            is ReportEvent.CompanyChosen -> change { it.copy(company = event.code) }
            is ReportEvent.PersonChosen -> change { it.copy(person = event.id) }
            is ReportEvent.ChannelChosen -> change { it.copy(channel = event.code) }
            is ReportEvent.OpenPicker -> {
                _state.update { it.copy(picking = event.pick, query = "", found = emptyList(), actionError = null) }
                search("")
            }
            ReportEvent.ClosePicker -> _state.update { it.copy(picking = null, query = "", found = emptyList()) }
            is ReportEvent.QueryChanged -> {
                _state.update { it.copy(query = event.value) }
                search(event.value)
            }
            is ReportEvent.CustomerChosen -> {
                _state.update { it.copy(picking = null, query = "", found = emptyList()) }
                change { it.copy(customer = event.id, customerName = event.name) }
            }
            is ReportEvent.ProductChosen -> {
                _state.update { it.copy(picking = null, query = "", found = emptyList()) }
                change { it.copy(product = event.id, productName = event.name) }
            }
            ReportEvent.Excel -> share("xlsx")
            ReportEvent.Pdf -> share("pdf")
        }
    }

    // A filter changed: keep it, but wait for Show before asking the server again.
    private fun change(update: (ReportFilters) -> ReportFilters) {
        _state.update { s ->
            val filters = update(s.filters)
            repository.remember(key, filters)
            s.copy(filters = filters, actionError = null)
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val catalogue = repository.catalogue()
                val info = catalogue.groups.flatMap { it.reports }.firstOrNull { it.key == key }
                if (info == null) {
                    _state.update { it.copy(loading = false, error = getString(Res.string.reports_no_match)) }
                    return@launch
                }
                _state.update {
                    it.copy(
                        loading = false,
                        info = info,
                        people = catalogue.people,
                        companies = app.user?.companies.orEmpty(),
                        // Back on a report, the filters it was last run with are still there.
                        filters = repository.remembered(key) ?: ReportFilters(),
                    )
                }
                // Nothing to pick first, so it runs at once with the server's own dates.
                if (_state.value.missing.isEmpty()) run()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }

    private fun run() {
        val s = _state.value
        val info = s.info ?: return
        if (s.running) return
        val missing = s.missing.firstOrNull()
        if (missing != null) {
            viewModelScope.launch { _state.update { it.copy(actionError = getString(needMessage(missing))) } }
            return
        }
        runJob?.cancel()
        _state.update { it.copy(running = true, actionError = null) }
        runJob = viewModelScope.launch {
            try {
                val result = repository.run(key, s.filters)
                // The first run tells the phone which dates the server used, so the pickers show them.
                val filters = if (info.uses("dates")) {
                    s.filters.copy(from = s.filters.from.ifBlank { result.from }, to = s.filters.to.ifBlank { result.to })
                } else {
                    s.filters
                }
                repository.remember(key, filters)
                _state.update { it.copy(running = false, result = result, filters = filters, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server's own words: dates the wrong way round, a company that is not theirs.
                _state.update { it.copy(running = false, actionError = app.handleApiError(e)) }
            }
        }
    }

    // The same report as a file, handed to the phone's share sheet for WhatsApp or printing.
    private fun share(format: String) {
        val s = _state.value
        val info = s.info ?: return
        if (s.busy) return
        if (s.missing.isNotEmpty()) {
            viewModelScope.launch { _state.update { it.copy(actionError = getString(needMessage(s.missing.first()))) } }
            return
        }
        _state.update { it.copy(sharing = format, actionError = null) }
        viewModelScope.launch {
            try {
                val file = repository.file(key, s.filters, format)
                val opened = documents.open(fileName(info, s, format), file.bytes, file.contentType, share = true)
                _state.update {
                    it.copy(sharing = null, actionError = if (opened) null else getString(Res.string.report_no_viewer))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(sharing = null, actionError = app.handleApiError(e)) }
            }
        }
    }

    // A name the person will recognise in WhatsApp or their downloads.
    private fun fileName(info: ReportInfo, s: ReportState, format: String): String {
        val title = info.title.map { if (it.isLetterOrDigit() || it == ' ') it else ' ' }.joinToString("").trim()
        val to = s.result?.to.orEmpty().ifBlank { s.filters.to }
        return listOf(title, to).filter { it.isNotBlank() }.joinToString(" ") + ".$format"
    }

    // Customers and products are searched on the server, within the company picked.
    private fun search(query: String) {
        val pick = _state.value.picking ?: return
        searchJob?.cancel()
        _state.update { it.copy(searching = true) }
        searchJob = viewModelScope.launch {
            try {
                val company = _state.value.filters.company
                val q = query.trim().ifBlank { null }
                val found = when (pick) {
                    ReportPick.Customer -> lists.customers(q, company, false).map { PickItem(it.id, it.name, it.area) }
                    ReportPick.Product -> lists.products(q, company, false).map { product ->
                        PickItem(product.id, listOf(product.brand, product.name, product.packSize).filter { it.isNotBlank() }.joinToString(" "), product.code)
                    }
                }
                _state.update { it.copy(searching = false, found = found) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, actionError = app.handleApiError(e)) }
            }
        }
    }
}

// What to say when a report cannot run until something is picked. The screen says the same thing
// before the first run, so the person is never left looking at an empty page.
fun needMessage(filter: String): StringResource = when (filter) {
    "company" -> Res.string.report_need_company
    "customer" -> Res.string.report_need_customer
    "person" -> Res.string.report_need_person
    "product" -> Res.string.report_need_product
    "channel" -> Res.string.report_need_channel
    else -> Res.string.report_need_dates
}
