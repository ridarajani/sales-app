package com.salesapp.ui.cash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.CashCompany
import com.salesapp.data.HandoverDetail
import com.salesapp.data.HandoverExpense
import com.salesapp.data.HandoverListItem
import com.salesapp.data.HeldCollection
import com.salesapp.resources.Res
import com.salesapp.resources.cash_expenses_list
import com.salesapp.resources.cash_from_airtel
import com.salesapp.resources.cash_from_payments
import com.salesapp.resources.cash_less_expenses
import com.salesapp.resources.cash_payments_list
import com.salesapp.resources.cash_per_company
import com.salesapp.resources.count_brought
import com.salesapp.resources.count_counted
import com.salesapp.resources.count_no_access
import com.salesapp.resources.count_title
import com.salesapp.resources.count_waiting
import com.salesapp.resources.count_waiting_none
import com.salesapp.resources.handover_count
import com.salesapp.resources.handover_counted_amount
import com.salesapp.resources.handover_counted_by
import com.salesapp.resources.handover_counting
import com.salesapp.resources.handover_brought_by
import com.salesapp.resources.handover_expected
import com.salesapp.resources.handover_expected_label
import com.salesapp.resources.handover_how_much
import com.salesapp.resources.handover_matches
import com.salesapp.resources.handover_note
import com.salesapp.resources.handover_over
import com.salesapp.resources.handover_own
import com.salesapp.resources.handover_short
import com.salesapp.resources.handover_title
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CountCashPage(
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    viewModel: CountCashViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(CountCashEvent.Refresh)
        onPauseOrDispose {}
    }
    CountCashScreen(state, viewModel::onEvent, onBack, onOpen)
}

// The cashier's list: cash waiting to be counted first, then what is already counted.
@Composable
fun CountCashScreen(
    state: CountCashState,
    onEvent: (CountCashEvent) -> Unit,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.count_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.count_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.waiting.isEmpty() && state.counted.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(CountCashEvent.Retry) }) }
                state.waiting.isEmpty() && state.counted.isEmpty() -> item { EmptyState(stringResource(Res.string.count_waiting_none)) }
                else -> {
                    if (state.waiting.isEmpty()) {
                        item { Say(stringResource(Res.string.count_waiting_none)) }
                    } else {
                        items(state.waiting, key = { "w${it.id}" }) { handover ->
                            HandoverRow(handover, onClick = { onOpen(handover.id) })
                        }
                    }
                    if (state.counted.isNotEmpty()) {
                        item { SectionLabel(stringResource(Res.string.count_counted), modifier = Modifier.padding(top = 8.dp)) }
                        items(state.counted, key = { "c${it.id}" }) { handover ->
                            HandoverRow(handover, onClick = { onOpen(handover.id) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HandoverRow(handover: HandoverListItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.count_brought, handover.handedBy, formatMwk(handover.net.toLong())),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Badge(
                    stringResource(if (handover.waiting) Res.string.count_waiting else Res.string.count_counted),
                    tone = if (handover.waiting) BadgeTone.Off else BadgeTone.On,
                )
            }
            Text(handover.number, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            Text(
                formatIsoDate(localIsoDateFromUtcTimestamp(handover.createdAt)),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.caption,
            )
            handover.difference?.takeIf { it != 0.0 }?.let { difference ->
                Text(
                    stringResource(
                        if (difference < 0) Res.string.handover_short else Res.string.handover_over,
                        formatMwk(if (difference < 0) -difference.toLong() else difference.toLong()),
                    ),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
fun HandoverDetailPage(
    id: Int,
    onBack: () -> Unit,
    viewModel: HandoverDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    HandoverDetailScreen(state, viewModel::onEvent, onBack)
}

// One handover: who brought it, what the app expects, the split per company, and the count.
@Composable
fun HandoverDetailScreen(state: HandoverDetailState, onEvent: (HandoverDetailEvent) -> Unit, onBack: () -> Unit) {
    val handover = state.handover
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(handover?.let { stringResource(Res.string.handover_title, it.number) } ?: "", onBack = onBack)
        when {
            handover != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BigMoney(stringResource(Res.string.handover_brought_by, handover.handedBy), handover.net)
                    ListCard(onClick = null) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MoneyRow(stringResource(Res.string.cash_from_payments), handover.cash)
                            if (handover.withdrawals > 0) MoneyRow(stringResource(Res.string.cash_from_airtel), handover.withdrawals)
                            if (handover.expenses > 0) MoneyRow(stringResource(Res.string.cash_less_expenses), handover.expenses, negative = true)
                            MoneyRow(stringResource(Res.string.handover_expected_label), handover.net, strong = true)
                        }
                    }
                    if (handover.companies.size > 1) {
                        SectionLabel(stringResource(Res.string.cash_per_company))
                        ListCard(onClick = null) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                handover.companies.forEach { company -> CompanySplit(company) }
                            }
                        }
                    }
                    if (handover.collections.isNotEmpty()) {
                        SectionLabel(stringResource(Res.string.cash_payments_list))
                        ListCard(onClick = null) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                handover.collections.forEach { payment -> PaymentLine(payment) }
                            }
                        }
                    }
                    if (handover.expenseList.isNotEmpty()) {
                        SectionLabel(stringResource(Res.string.cash_expenses_list))
                        ListCard(onClick = null) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                handover.expenseList.forEach { expense -> HandoverExpenseLine(expense) }
                            }
                        }
                    }
                    if (handover.waiting) {
                        if (handover.canCount) {
                            // What the app expects, so the cashier can compare without it being typed for them.
                            Text(
                                stringResource(Res.string.handover_expected, formatMwk(handover.net.toLong())),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            MwkField(
                                label = stringResource(Res.string.handover_how_much),
                                amount = state.counted,
                                onAmountChange = { onEvent(HandoverDetailEvent.CountedChanged(it)) },
                            )
                            Difference(state)
                            Field(
                                label = stringResource(Res.string.handover_note),
                                value = state.note,
                                onValueChange = { onEvent(HandoverDetailEvent.NoteChanged(it)) },
                                enabled = !state.busy,
                            )
                        } else {
                            // The person who handed it over cannot count their own cash.
                            Say(stringResource(Res.string.handover_own))
                        }
                    } else {
                        Counted(handover)
                    }
                    state.actionError?.let { Say(it, error = true) }
                }
                // The button only shows while this cash is waiting and this user may count it.
                if (handover.waiting && handover.canCount) {
                    PrimaryButton(
                        stringResource(if (state.busy) Res.string.handover_counting else Res.string.handover_count),
                        onClick = { onEvent(HandoverDetailEvent.Count) },
                        enabled = !state.busy,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(HandoverDetailEvent.Retry) })
            else -> LoadingState()
        }
    }
}

// What the typed count is out by, before it is sent.
@Composable
private fun Difference(state: HandoverDetailState) {
    val difference = state.difference ?: return
    val text = when {
        difference == 0L -> stringResource(Res.string.handover_matches)
        difference < 0 -> stringResource(Res.string.handover_short, formatMwk(-difference))
        else -> stringResource(Res.string.handover_over, formatMwk(difference))
    }
    Say(text, error = difference != 0L)
}

@Composable
private fun Counted(handover: HandoverDetail) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        handover.counted?.let {
            Text(
                stringResource(Res.string.handover_counted_amount, formatMwk(it.toLong())),
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        val difference = handover.difference ?: 0.0
        Text(
            when {
                difference == 0.0 -> stringResource(Res.string.handover_matches)
                difference < 0 -> stringResource(Res.string.handover_short, formatMwk(-difference.toLong()))
                else -> stringResource(Res.string.handover_over, formatMwk(difference.toLong()))
            },
            color = if (difference == 0.0) scheme.onSurfaceVariant else scheme.error,
            style = MaterialTheme.typography.bodyMedium,
        )
        if (handover.note.isNotBlank()) {
            Text(handover.note, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (handover.countedBy != null && handover.countedAt != null) {
            Text(
                stringResource(
                    Res.string.handover_counted_by,
                    handover.countedBy,
                    formatIsoDate(localIsoDateFromUtcTimestamp(handover.countedAt)),
                ),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
private fun CompanySplit(company: CashCompany) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CompanyDot(company.code, modifier = Modifier.weight(1f))
        Text(formatMwk(company.net.toLong()), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PaymentLine(payment: HeldCollection) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(payment.customerName, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text("${payment.number} · ${payment.companyCode}", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
        }
        Text(formatMwk(payment.amount.toLong()), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun HandoverExpenseLine(expense: HandoverExpense) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(expense.description, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text("${expense.number} · ${expense.companyCode}", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
        }
        Text("− ${formatMwk(expense.amount.toLong())}", color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

private val sampleHandover = HandoverDetail(
    id = 3,
    number = "HO-000013",
    handedById = 4,
    handedBy = "Chimwemwe Banda",
    cash = 796_000.0,
    withdrawals = 200_000.0,
    expenses = 45_000.0,
    net = 951_000.0,
    status = "handed",
    createdAt = "2026-10-24T16:30:00.000Z",
    collections = listOf(
        HeldCollection(id = 1, number = "LV-RCT-000007", companyCode = "LV", customerName = "Area 47 Superstore", amount = 700_000.0),
        HeldCollection(id = 2, number = "ZT-RCT-000004", companyCode = "ZT", customerName = "Kanengo Wholesalers", amount = 96_000.0),
    ),
    expenseList = listOf(HandoverExpense(id = 2, number = "LV-EXP-000004", companyCode = "LV", description = "Fuel for LL 4521", amount = 45_000.0)),
    companies = listOf(
        CashCompany(code = "LV", cash = 700_000.0, withdrawals = 200_000.0, expenses = 45_000.0, net = 855_000.0),
        CashCompany(code = "ZT", cash = 96_000.0, net = 96_000.0),
    ),
    canCount = true,
)

@Preview
@Composable
private fun CountCashPreview() {
    SalesTheme {
        CountCashScreen(
            state = CountCashState(
                loading = false,
                waiting = listOf(HandoverListItem(id = 3, number = "HO-000013", handedBy = "Chimwemwe Banda", net = 951_000.0, createdAt = "2026-10-24T16:30:00.000Z")),
                counted = listOf(HandoverListItem(id = 2, number = "HO-000012", handedBy = "Mr. Abbas", net = 420_000.0, status = "counted", counted = 415_000.0, difference = -5_000.0, createdAt = "2026-10-23T16:00:00.000Z")),
            ),
            onEvent = {},
            onBack = {},
            onOpen = {},
        )
    }
}

@Preview
@Composable
private fun HandoverCountPreview() {
    SalesTheme {
        HandoverDetailScreen(
            state = HandoverDetailState(loading = false, handover = sampleHandover),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun HandoverCountingPreview() {
    SalesTheme {
        HandoverDetailScreen(
            state = HandoverDetailState(loading = false, handover = sampleHandover, counted = 946_000),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun HandoverCountedPreview() {
    SalesTheme {
        HandoverDetailScreen(
            state = HandoverDetailState(
                loading = false,
                handover = sampleHandover.copy(
                    status = "counted", counted = 946_000.0, difference = -5_000.0, note = "One shop paid 5,000 less than the receipt.",
                    countedBy = "Thoko Mwale", countedAt = "2026-10-24T17:10:00.000Z", canCount = false,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun HandoverOwnPreview() {
    SalesTheme {
        HandoverDetailScreen(
            state = HandoverDetailState(loading = false, handover = sampleHandover.copy(canCount = false)),
            onEvent = {},
            onBack = {},
        )
    }
}
