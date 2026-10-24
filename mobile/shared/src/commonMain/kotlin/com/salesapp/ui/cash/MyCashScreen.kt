package com.salesapp.ui.cash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.salesapp.data.CashHeld
import com.salesapp.data.Expense
import com.salesapp.data.HandoverListItem
import com.salesapp.data.HeldCollection
import com.salesapp.data.HeldWithdrawal
import com.salesapp.resources.Res
import com.salesapp.resources.cash_company_row
import com.salesapp.resources.cash_expenses_list
import com.salesapp.resources.cash_from_airtel
import com.salesapp.resources.cash_from_payments
import com.salesapp.resources.cash_hand_over
import com.salesapp.resources.cash_handed_over
import com.salesapp.resources.cash_handing_over
import com.salesapp.resources.cash_less_expenses
import com.salesapp.resources.cash_no_access
import com.salesapp.resources.cash_nothing
import com.salesapp.resources.cash_past
import com.salesapp.resources.cash_payments_list
import com.salesapp.resources.cash_per_company
import com.salesapp.resources.cash_title
import com.salesapp.resources.cash_to_hand_over
import com.salesapp.resources.cash_withdrawals_list
import com.salesapp.resources.count_counted
import com.salesapp.resources.count_waiting
import com.salesapp.resources.handover_counted_amount
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun MyCashPage(
    onBack: () -> Unit,
    viewModel: MyCashViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(MyCashEvent.Refresh)
        onPauseOrDispose {}
    }
    MyCashScreen(state, viewModel::onEvent, onBack)
}

// What this person holds: one net total, then each company behind it, then the lists it is made of.
@Composable
fun MyCashScreen(state: MyCashState, onEvent: (MyCashEvent) -> Unit, onBack: () -> Unit) {
    val held = state.held
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.cash_title), onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.cash_no_access), error = true, modifier = Modifier.padding(16.dp))
            held != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    BigMoney(stringResource(Res.string.cash_to_hand_over), held.net)
                    state.handedOver?.let { Say(stringResource(Res.string.cash_handed_over, it)) }
                    if (!state.hasSomething) {
                        Say(stringResource(Res.string.cash_nothing))
                    } else {
                        ListCard(onClick = null) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                MoneyRow(stringResource(Res.string.cash_from_payments), held.cash)
                                if (held.withdrawalsTotal > 0) MoneyRow(stringResource(Res.string.cash_from_airtel), held.withdrawalsTotal)
                                if (held.expensesTotal > 0) {
                                    MoneyRow(stringResource(Res.string.cash_less_expenses), held.expensesTotal, negative = true)
                                }
                                MoneyRow(stringResource(Res.string.cash_to_hand_over), held.net, strong = true)
                            }
                        }
                        if (held.companies.size > 1) {
                            SectionLabel(stringResource(Res.string.cash_per_company))
                            ListCard(onClick = null) {
                                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    held.companies.forEach { company -> CompanyCash(company) }
                                }
                            }
                        }
                        Lists(held)
                    }
                    state.actionError?.let { Say(it, error = true) }
                    Past(state.past)
                }
                PrimaryButton(
                    stringResource(
                        if (state.busy) Res.string.cash_handing_over else Res.string.cash_hand_over,
                        formatMwk(held.net.toLong()),
                    ),
                    onClick = { onEvent(MyCashEvent.HandOver) },
                    enabled = !state.busy && state.hasSomething,
                    modifier = Modifier.padding(16.dp),
                )
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(MyCashEvent.Retry) })
            else -> LoadingState()
        }
    }
}

// One company's share: cash plus what was taken out of Airtel, less cash expenses.
@Composable
private fun CompanyCash(company: CashCompany) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            CompanyDot(company.code, modifier = Modifier.weight(1f))
            Text(
                formatMwk(company.net.toLong()),
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
        }
        Text(
            stringResource(
                Res.string.cash_company_row,
                formatMwk(company.cash.toLong()),
                formatMwk(company.withdrawals.toLong()),
                formatMwk(company.expenses.toLong()),
            ),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.caption,
        )
    }
}

@Composable
private fun Lists(held: CashHeld) {
    val scheme = MaterialTheme.colorScheme
    if (held.collections.isNotEmpty()) {
        SectionLabel(stringResource(Res.string.cash_payments_list))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                held.collections.forEach { payment -> HeldRow(payment) }
            }
        }
    }
    if (held.withdrawals.isNotEmpty()) {
        SectionLabel(stringResource(Res.string.cash_withdrawals_list))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                held.withdrawals.forEach { withdrawal -> WithdrawalRow(withdrawal) }
            }
        }
    }
    if (held.expenses.isNotEmpty()) {
        SectionLabel(stringResource(Res.string.cash_expenses_list))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                held.expenses.forEach { expense -> ExpenseLine(expense) }
            }
        }
    }
}

@Composable
private fun HeldRow(payment: HeldCollection) {
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
private fun WithdrawalRow(withdrawal: HeldWithdrawal) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        CompanyDot(withdrawal.companyCode, modifier = Modifier.weight(1f))
        Text(formatMwk(withdrawal.amount.toLong()), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ExpenseLine(expense: Expense) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(expense.description, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text("${expense.number} · ${expense.companyCode}", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
        }
        Text("− ${formatMwk(expense.amount.toLong())}", color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Past(past: List<HandoverListItem>) {
    if (past.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.cash_past))
        past.take(10).forEach { handover ->
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(handover.number, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Badge(
                            stringResource(if (handover.waiting) Res.string.count_waiting else Res.string.count_counted),
                            tone = if (handover.waiting) BadgeTone.Off else BadgeTone.On,
                        )
                    }
                    Text(formatMwk(handover.net.toLong()), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                    handover.counted?.let {
                        Text(
                            stringResource(Res.string.handover_counted_amount, formatMwk(it.toLong())),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.caption,
                        )
                    }
                    Text(
                        formatIsoDate(localIsoDateFromUtcTimestamp(handover.createdAt)),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
        }
    }
}

private val sampleHeld = CashHeld(
    collections = listOf(
        HeldCollection(id = 1, number = "LV-RCT-000007", companyCode = "LV", customerName = "Area 47 Superstore", amount = 700_000.0),
        HeldCollection(id = 2, number = "ZT-RCT-000004", companyCode = "ZT", customerName = "Kanengo Wholesalers", amount = 96_000.0),
    ),
    withdrawals = listOf(HeldWithdrawal(id = 5, companyCode = "LV", amount = 200_000.0)),
    expenses = listOf(Expense(id = 2, number = "LV-EXP-000004", companyCode = "LV", amount = 45_000.0, description = "Fuel for LL 4521")),
    companies = listOf(
        CashCompany(code = "LV", cash = 700_000.0, withdrawals = 200_000.0, expenses = 45_000.0, net = 855_000.0),
        CashCompany(code = "ZT", cash = 96_000.0, net = 96_000.0),
    ),
    cash = 796_000.0,
    withdrawalsTotal = 200_000.0,
    expensesTotal = 45_000.0,
    net = 951_000.0,
)

@Preview
@Composable
private fun MyCashPreview() {
    SalesTheme {
        MyCashScreen(
            state = MyCashState(
                loading = false,
                held = sampleHeld,
                past = listOf(
                    HandoverListItem(id = 2, number = "HO-000012", handedBy = "You", net = 420_000.0, status = "counted", counted = 420_000.0, createdAt = "2026-10-22T16:00:00.000Z"),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun MyCashEmptyPreview() {
    SalesTheme {
        MyCashScreen(state = MyCashState(loading = false, held = CashHeld()), onEvent = {}, onBack = {})
    }
}
