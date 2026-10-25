package com.salesapp.ui.reports

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.channelLabel
import com.salesapp.core.formatMwk
import com.salesapp.core.formatSlashDate
import com.salesapp.core.formatThousands
import com.salesapp.data.ReportColumn
import com.salesapp.data.ReportFilters
import com.salesapp.data.ReportInfo
import com.salesapp.data.ReportPerson
import com.salesapp.data.ReportResult
import com.salesapp.data.cell
import com.salesapp.data.companyName
import com.salesapp.data.number
import com.salesapp.resources.Res
import com.salesapp.resources.report_change
import com.salesapp.resources.report_channel
import com.salesapp.resources.report_company
import com.salesapp.resources.report_company_all
import com.salesapp.resources.report_customer
import com.salesapp.resources.report_customer_none
import com.salesapp.resources.report_customer_pick
import com.salesapp.resources.report_customer_search
import com.salesapp.resources.report_dates_from
import com.salesapp.resources.report_dates_to
import com.salesapp.resources.report_empty
import com.salesapp.resources.report_excel
import com.salesapp.resources.report_pdf
import com.salesapp.resources.report_person_all
import com.salesapp.resources.report_product
import com.salesapp.resources.report_product_none
import com.salesapp.resources.report_product_pick
import com.salesapp.resources.report_product_search
import com.salesapp.resources.report_rows
import com.salesapp.resources.report_rows_one
import com.salesapp.resources.report_sharing
import com.salesapp.resources.report_show
import com.salesapp.resources.report_showing
import com.salesapp.resources.report_totals
import com.salesapp.resources.report_truncated
import com.salesapp.resources.reports_title
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.DateField
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ReportPage(
    key: String,
    onBack: () -> Unit,
    viewModel: ReportViewModel = koinViewModel { parametersOf(key) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ReportScreen(state, viewModel::onEvent, onBack)
}

// One screen for all 53 reports: the catalogue says which filters it takes, and the columns say how
// each row is written. Rows are cards, because a wide table cannot be read on a phone.
@Composable
fun ReportScreen(state: ReportState, onEvent: (ReportEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            state.title.ifBlank { stringResource(Res.string.reports_title) },
            // While picking, back closes the list and leaves the filters as they are.
            onBack = { if (state.picking != null) onEvent(ReportEvent.ClosePicker) else onBack() },
        )
        when {
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(ReportEvent.Retry) })
            state.picking != null -> Picker(state, onEvent)
            else -> {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "filters") { Filters(state, onEvent) }
                    if (state.totalColumns.isNotEmpty()) item(key = "totals") { TotalsCard(state) }
                    if (state.result?.truncated == true) {
                        item(key = "truncated") { Say(stringResource(Res.string.report_truncated, state.rows.size), error = true) }
                    }
                    if (state.empty) item(key = "empty") { EmptyState(stringResource(Res.string.report_empty)) }
                    if (state.rows.isNotEmpty()) {
                        item(key = "count") {
                            Text(
                                if (state.rows.size == 1) {
                                    stringResource(Res.string.report_rows_one)
                                } else {
                                    stringResource(Res.string.report_rows, state.rows.size)
                                },
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                    }
                    items(state.rows.size, key = { it }) { index -> RowCard(state, state.rows[index]) }
                }
                // Excel for the office, PDF for printing or WhatsApp.
                Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        stringResource(if (state.sharing == "xlsx") Res.string.report_sharing else Res.string.report_excel),
                        onClick = { onEvent(ReportEvent.Excel) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        stringResource(if (state.sharing == "pdf") Res.string.report_sharing else Res.string.report_pdf),
                        onClick = { onEvent(ReportEvent.Pdf) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

// Only the filters this report takes, in the order the office reads them.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Filters(state: ReportState, onEvent: (ReportEvent) -> Unit) {
    val info = state.info ?: return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (info.uses("dates")) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DateField(
                    label = stringResource(Res.string.report_dates_from),
                    isoDate = state.filters.from,
                    onDateChange = { onEvent(ReportEvent.FromChanged(it)) },
                    modifier = Modifier.weight(1f),
                )
                DateField(
                    label = stringResource(Res.string.report_dates_to),
                    isoDate = state.filters.to,
                    onDateChange = { onEvent(ReportEvent.ToChanged(it)) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (info.uses("company")) {
            SectionLabel(stringResource(Res.string.report_company))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // A report that must have one company picked is never shown "All".
                if (!info.needs("company")) {
                    ChoiceButton(
                        text = stringResource(Res.string.report_company_all),
                        selected = state.filters.company == null,
                        onClick = { onEvent(ReportEvent.CompanyChosen(null)) },
                        enabled = !state.busy,
                    )
                }
                state.companies.forEach { code ->
                    ChoiceButton(
                        text = companyName(code),
                        selected = state.filters.company == code,
                        onClick = { onEvent(ReportEvent.CompanyChosen(code)) },
                        enabled = !state.busy,
                    )
                }
            }
        }
        if (info.uses("person") && state.people.isNotEmpty()) {
            SectionLabel(info.personLabel)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!info.needs("person")) {
                    ChoiceButton(
                        text = stringResource(Res.string.report_person_all),
                        selected = state.filters.person == null,
                        onClick = { onEvent(ReportEvent.PersonChosen(null)) },
                        enabled = !state.busy,
                    )
                }
                state.people.forEach { person ->
                    ChoiceButton(
                        text = person.name,
                        selected = state.filters.person == person.id,
                        onClick = { onEvent(ReportEvent.PersonChosen(person.id)) },
                        enabled = !state.busy,
                    )
                }
            }
        }
        if (info.uses("customer")) {
            PickedRow(
                label = stringResource(Res.string.report_customer),
                value = state.filters.customerName.ifBlank { stringResource(Res.string.report_customer_pick) },
                busy = state.busy,
                onChange = { onEvent(ReportEvent.OpenPicker(ReportPick.Customer)) },
            )
        }
        if (info.uses("product")) {
            PickedRow(
                label = stringResource(Res.string.report_product),
                value = state.filters.productName.ifBlank { stringResource(Res.string.report_product_pick) },
                busy = state.busy,
                onChange = { onEvent(ReportEvent.OpenPicker(ReportPick.Product)) },
            )
        }
        if (info.uses("channel")) {
            SectionLabel(stringResource(Res.string.report_channel))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceButton(
                    text = stringResource(Res.string.report_company_all),
                    selected = state.filters.channel == null,
                    onClick = { onEvent(ReportEvent.ChannelChosen(null)) },
                    enabled = !state.busy,
                )
                listOf("supermarket", "wholesaler", "minibus", "upcountry").forEach { code ->
                    ChoiceButton(
                        text = channelLabel(code),
                        selected = state.filters.channel == code,
                        onClick = { onEvent(ReportEvent.ChannelChosen(code)) },
                        enabled = !state.busy,
                    )
                }
            }
        }
        // What the server made of the filters, in its own words.
        state.result?.picked?.takeIf { it.isNotBlank() }?.let {
            Text(it, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        // Some reports cannot run until something is picked; say so before anyone taps Show.
        val missing = state.missing.firstOrNull()
        if (missing != null && state.result == null && state.actionError == null) {
            Say(stringResource(needMessage(missing)))
        }
        state.actionError?.let { Say(it, error = true) }
        PrimaryButton(
            stringResource(if (state.running) Res.string.report_showing else Res.string.report_show),
            onClick = { onEvent(ReportEvent.Show) },
            enabled = !state.busy,
        )
    }
}

// A filter that opens a search: the customer or the product.
@Composable
private fun PickedRow(label: String, value: String, busy: Boolean, onChange: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        SectionLabel(label)
        ListCard(onClick = null) {
            Text(
                value,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                stringResource(Res.string.report_change),
                onClick = onChange,
                enabled = !busy,
                modifier = Modifier.weight(0.5f),
            )
        }
    }
}

// The customer or product search, over the whole screen while it is open.
@Composable
private fun ColumnScope.Picker(state: ReportState, onEvent: (ReportEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val customers = state.picking == ReportPick.Customer
    SearchField(
        value = state.query,
        onValueChange = { onEvent(ReportEvent.QueryChanged(it)) },
        placeholder = stringResource(if (customers) Res.string.report_customer_search else Res.string.report_product_search),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.searching && state.found.isEmpty() -> item { LoadingState() }
            state.found.isEmpty() -> item {
                EmptyState(stringResource(if (customers) Res.string.report_customer_none else Res.string.report_product_none))
            }
            else -> items(state.found, key = { it.id }) { item ->
                ListCard(
                    onClick = {
                        if (customers) onEvent(ReportEvent.CustomerChosen(item.id, item.title))
                        else onEvent(ReportEvent.ProductChosen(item.id, item.title))
                    },
                ) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(item.title, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
                        if (item.note.isNotBlank()) {
                            Text(item.note, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

// The totals of the whole report, in burgundy at the top. Money carries MWK only here.
@Composable
private fun TotalsCard(state: ReportState) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(scheme.primary).padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            stringResource(Res.string.report_totals).uppercase(),
            color = scheme.onPrimary.copy(alpha = 0.85f),
            style = MaterialTheme.typography.labelMedium,
        )
        state.totalColumns.forEach { column ->
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    column.title,
                    color = scheme.onPrimary.copy(alpha = 0.85f),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    totalText(column, state.totals[column.key] ?: 0.0),
                    color = scheme.onPrimary,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

// One row of the report. The first words-columns are the heading; the rest are label and value.
@Composable
private fun RowCard(state: ReportState, row: JsonObject) {
    val scheme = MaterialTheme.colorScheme
    val heading = state.headingColumns.map { row.cell(it.key) }.filter { it.isNotBlank() }
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            heading.forEachIndexed { index, text ->
                Text(
                    text,
                    color = if (index == 0) scheme.onSurface else scheme.onSurfaceVariant,
                    style = if (index == 0) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                )
            }
            state.detailColumns.forEach { column ->
                val text = cellText(column, row)
                // Nothing to show is left out, so the card stays short.
                if (text.isNotBlank()) {
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            column.title,
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            text,
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (column.isNumber) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

// How one value is written: whole kwacha with thousands separators, a date as dd/MM/yyyy, a
// percentage with its sign, and quantities as the server already wrote them.
private fun cellText(column: ReportColumn, row: JsonObject): String {
    val raw = row.cell(column.key)
    if (raw.isBlank()) return ""
    return when (column.kind) {
        "money", "int" -> row.number(column.key)?.let { formatThousands(it.toLong()) } ?: raw
        "percent" -> row.number(column.key)?.let { "${formatThousands(it.toLong())}%" } ?: raw
        "date" -> formatSlashDate(raw)
        else -> raw
    }
}

private fun totalText(column: ReportColumn, value: Double): String = when (column.kind) {
    "money" -> formatMwk(value.toLong())
    "percent" -> "${formatThousands(value.toLong())}%"
    else -> formatThousands(value.toLong())
}

// --- Previews ---

private val sampleColumns = listOf(
    ReportColumn("customer", "Customer", "text"),
    ReportColumn("area", "Area", "text"),
    ReportColumn("invoiceDate", "Date", "date"),
    ReportColumn("cartons", "Cartons", "qty"),
    ReportColumn("subtotal", "Before VAT", "money", total = true),
    ReportColumn("total", "Total", "money", total = true),
)

private fun sampleRow(customer: String, area: String, date: String, qty: String, subtotal: Int, total: Int) = buildJsonObject {
    put("customer", JsonPrimitive(customer))
    put("area", JsonPrimitive(area))
    put("invoiceDate", JsonPrimitive(date))
    put("cartons", JsonPrimitive(qty))
    put("subtotal", JsonPrimitive(subtotal))
    put("total", JsonPrimitive(total))
}

private val sampleResult = ReportResult(
    key = "sales-by-customer",
    title = "Sales by customer",
    picked = "1 Oct 2026 to 25 Oct 2026 · Lucky Ventures",
    from = "2026-10-01",
    to = "2026-10-25",
    columns = sampleColumns,
    rows = listOf(
        sampleRow("Area 47 Superstore", "Area 47, Lilongwe", "2026-10-04", "12 ctn", 1_200_000, 1_392_000),
        sampleRow("Salima Shop", "Salima", "2026-10-11", "4 ctn 6 pcs", 430_000, 498_800),
    ),
    totals = mapOf("subtotal" to 1_630_000.0, "total" to 1_890_800.0),
)

private fun sampleState(result: ReportResult? = sampleResult) = ReportState(
    key = "sales-by-customer",
    loading = false,
    info = ReportInfo(
        key = "sales-by-customer",
        title = "Sales by customer",
        description = "What each customer bought, before VAT",
        filters = listOf("dates", "company", "person", "customer", "channel"),
        personLabel = "Salesman",
    ),
    people = listOf(ReportPerson(4, "Chimwemwe Banda", "salesman"), ReportPerson(3, "T. Mwale", "sales_manager")),
    companies = listOf("LV", "ZT"),
    filters = ReportFilters(from = "2026-10-01", to = "2026-10-25", company = "LV"),
    result = result,
)

@Preview
@Composable
private fun ReportPreview() {
    SalesTheme { ReportScreen(sampleState(), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun ReportEmptyPreview() {
    SalesTheme {
        ReportScreen(
            sampleState(sampleResult.copy(rows = emptyList(), totals = emptyMap())),
            onEvent = {},
            onBack = {},
        )
    }
}

// A report that cannot run until the company and the customer are picked.
@Preview
@Composable
private fun ReportNeedsPickPreview() {
    SalesTheme {
        ReportScreen(
            ReportState(
                key = "customer-ledger",
                loading = false,
                info = ReportInfo(
                    key = "customer-ledger",
                    title = "Customer ledger",
                    description = "Everything one customer bought and paid",
                    filters = listOf("company", "customer"),
                    required = listOf("company", "customer"),
                ),
                companies = listOf("LV", "ZT"),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ReportPickerPreview() {
    SalesTheme {
        ReportScreen(
            sampleState().copy(
                picking = ReportPick.Customer,
                found = listOf(PickItem(1, "Area 47 Superstore", "Area 47, Lilongwe"), PickItem(2, "Salima Shop", "Salima")),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
