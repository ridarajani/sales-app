package com.salesapp.ui.ledger

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.data.Company
import com.salesapp.data.Customer
import com.salesapp.data.Ledger
import com.salesapp.data.LedgerCompany
import com.salesapp.data.LedgerCustomer
import com.salesapp.data.LedgerRow
import com.salesapp.resources.Res
import com.salesapp.resources.ledger_all
import com.salesapp.resources.ledger_balance
import com.salesapp.resources.ledger_change_customer
import com.salesapp.resources.ledger_empty
import com.salesapp.resources.ledger_getting
import com.salesapp.resources.ledger_last_30
import com.salesapp.resources.ledger_late
import com.salesapp.resources.ledger_no_access
import com.salesapp.resources.ledger_no_customers
import com.salesapp.resources.ledger_nothing_late
import com.salesapp.resources.ledger_opening
import com.salesapp.resources.ledger_owes_label
import com.salesapp.resources.ledger_pick
import com.salesapp.resources.ledger_search
import com.salesapp.resources.ledger_share
import com.salesapp.resources.ledger_this_month
import com.salesapp.resources.ledger_title
import com.salesapp.resources.ledger_which_company
import com.salesapp.ui.cash.BigMoney
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.FilterChips
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import com.salesapp.ui.theme.sales
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun LedgerPage(
    customerId: Int? = null,
    companyCode: String? = null,
    onBack: () -> Unit,
    viewModel: LedgerViewModel = koinViewModel { parametersOf(customerId, companyCode) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LedgerScreen(state, viewModel::onEvent, onBack)
}

// Every movement with one company, oldest first, with the balance after each. The statement for the
// same company and period goes out through the phone's share sheet.
@Composable
fun LedgerScreen(state: LedgerState, onEvent: (LedgerEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.ledger_title), onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.ledger_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.customer == null -> PickCustomer(state, onEvent)
            else -> Card(state, onEvent)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PickCustomer(state: LedgerState, onEvent: (LedgerEvent) -> Unit) {
    SectionLabel(stringResource(Res.string.ledger_pick), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    SearchField(
        value = state.query,
        onValueChange = { onEvent(LedgerEvent.QueryChanged(it)) },
        placeholder = stringResource(Res.string.ledger_search),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.loading || (state.searching && state.customers.isEmpty()) -> item { LoadingState() }
            state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(LedgerEvent.Retry) }) }
            state.customers.isEmpty() -> item { EmptyState(stringResource(Res.string.ledger_no_customers)) }
            else -> items(state.customers, key = { it.id }) { customer ->
                ListCard(onClick = { onEvent(LedgerEvent.CustomerChosen(customer.id)) }) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(customer.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                        if (customer.area.isNotBlank()) {
                            Text(customer.area, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Card(state: LedgerState, onEvent: (LedgerEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val ledger = state.ledger
    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            state.customer?.let {
                Text(it.name, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
            }
            if (state.companies.size > 1) {
                SectionLabel(stringResource(Res.string.ledger_which_company))
                ChoiceColumn {
                    state.companies.forEach { company ->
                        ChoiceButton(
                            text = company.name,
                            selected = state.companyCode == company.code,
                            onClick = { onEvent(LedgerEvent.CompanyChosen(company.code)) },
                            enabled = !state.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        }
        FilterChips(
            options = listOf(
                "month" to stringResource(Res.string.ledger_this_month),
                "30" to stringResource(Res.string.ledger_last_30),
            ),
            // "All" is the chips' own first option.
            selected = when (state.period) {
                LedgerPeriod.ThisMonth -> "month"
                LedgerPeriod.Last30 -> "30"
                LedgerPeriod.All -> null
            },
            onSelect = { chosen ->
                onEvent(
                    LedgerEvent.PeriodChosen(
                        when (chosen) {
                            "month" -> LedgerPeriod.ThisMonth
                            "30" -> LedgerPeriod.Last30
                            else -> LedgerPeriod.All
                        },
                    ),
                )
            },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && ledger == null -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(LedgerEvent.Retry) }) }
                ledger != null -> {
                    item { Owes(ledger) }
                    // What was owed before the period, so the balances read straight on.
                    if (ledger.from != null) {
                        item {
                            ListCard(onClick = null) {
                                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(Res.string.ledger_opening),
                                        color = scheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(formatMwk(ledger.opening.toLong()), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                                }
                            }
                        }
                    }
                    if (ledger.rows.isEmpty()) {
                        item { EmptyState(stringResource(Res.string.ledger_empty)) }
                    } else {
                        items(ledger.rows, key = { "${it.date}-${it.document}-${it.what}-${it.balance}" }) { row -> MovementCard(row) }
                    }
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.actionError?.let { Say(it, error = true) }
                            if (state.busy) {
                                Text(
                                    stringResource(Res.string.ledger_getting),
                                    color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            SecondaryButton(
                                stringResource(Res.string.ledger_change_customer),
                                onClick = { onEvent(LedgerEvent.ChangeCustomer) },
                                enabled = !state.busy,
                            )
                        }
                    }
                }
                else -> item { LoadingState() }
            }
        }
    }
    if (ledger != null) {
        SecondaryButton(
            stringResource(Res.string.ledger_share),
            onClick = { onEvent(LedgerEvent.Share) },
            enabled = !state.busy,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun Owes(ledger: Ledger) {
    val sales = MaterialTheme.sales
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BigMoney(stringResource(Res.string.ledger_owes_label), ledger.owes, warn = ledger.overdue > 0)
        Text(
            if (ledger.overdue > 0) {
                stringResource(Res.string.ledger_late, formatMwk(ledger.overdue.toLong()))
            } else {
                stringResource(Res.string.ledger_nothing_late)
            },
            color = if (ledger.overdue > 0) sales.bad else MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (ledger.overdue > 0) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

// One movement: what it was, what it charged or paid, and the balance after it.
@Composable
private fun MovementCard(row: LedgerRow) {
    val scheme = MaterialTheme.colorScheme
    val sales = MaterialTheme.sales
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    formatIsoDate(row.date),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.caption,
                    modifier = Modifier.weight(1f),
                )
                if (row.document.isNotBlank()) {
                    Text(row.document, color = scheme.onSurface, style = MaterialTheme.typography.caption)
                }
            }
            Text(row.what, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    // Charged puts the balance up, paid puts it down.
                    if (row.charged > 0) "+ ${formatMwk(row.charged.toLong())}" else "− ${formatMwk(row.paid.toLong())}",
                    color = if (row.charged > 0) scheme.onSurface else sales.good,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    stringResource(Res.string.ledger_balance, formatMwk(row.balance.toLong())),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    textAlign = TextAlign.End,
                )
            }
        }
    }
}

private val sampleLedger = Ledger(
    customer = LedgerCustomer(id = 1, name = "Area 47 Superstore", area = "Area 47"),
    company = LedgerCompany(code = "LV", name = "Lucky Ventures"),
    opening = 600_000.0,
    rows = listOf(
        LedgerRow(date = "2026-10-23", document = "LV-INV-000014", what = "Invoice · LPO LPO-5521", charged = 564_000.0, balance = 1_164_000.0, invoiceId = 3),
        LedgerRow(date = "2026-10-24", document = "LV-RCT-000007", what = "Payment · Cash", paid = 700_000.0, balance = 464_000.0, invoiceId = 3),
        LedgerRow(date = "2026-10-24", document = "LV-SR-000003", what = "Sales return · LV-INV-000014", paid = 105_750.0, balance = 358_250.0, invoiceId = 3),
    ),
    charged = 564_000.0,
    paid = 805_750.0,
    closing = 358_250.0,
    owes = 358_250.0,
    overdue = 120_000.0,
)

private val sampleCustomers = listOf(
    Customer(id = 1, name = "Area 47 Superstore", area = "Area 47"),
    Customer(id = 2, name = "Kanengo Wholesalers", area = "Kanengo"),
)

@Preview
@Composable
private fun LedgerPickPreview() {
    SalesTheme { LedgerScreen(LedgerState(customers = sampleCustomers), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun LedgerCardPreview() {
    SalesTheme {
        LedgerScreen(
            LedgerState(
                customer = sampleCustomers[0],
                companies = listOf(Company("LV", "Lucky Ventures", "F1"), Company("ZT", "Zoona Trading", "F1")),
                companyCode = "LV",
                ledger = sampleLedger,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun LedgerPeriodPreview() {
    SalesTheme {
        LedgerScreen(
            LedgerState(
                customer = sampleCustomers[0],
                companies = listOf(Company("LV", "Lucky Ventures", "F1")),
                companyCode = "LV",
                period = LedgerPeriod.Last30,
                ledger = sampleLedger.copy(from = "2026-09-25", overdue = 0.0),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
