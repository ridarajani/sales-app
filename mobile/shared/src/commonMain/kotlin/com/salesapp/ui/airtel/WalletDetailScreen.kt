package com.salesapp.ui.airtel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.WalletCheck
import com.salesapp.data.WalletDetail
import com.salesapp.data.WalletEntry
import com.salesapp.resources.Res
import com.salesapp.resources.airtel_admin_edit
import com.salesapp.resources.airtel_balance
import com.salesapp.resources.airtel_balance_after
import com.salesapp.resources.airtel_check
import com.salesapp.resources.airtel_check_actual
import com.salesapp.resources.airtel_check_diff
import com.salesapp.resources.airtel_check_note
import com.salesapp.resources.airtel_check_row
import com.salesapp.resources.airtel_check_same
import com.salesapp.resources.airtel_check_save
import com.salesapp.resources.airtel_check_title
import com.salesapp.resources.airtel_checked_by
import com.salesapp.resources.airtel_checks
import com.salesapp.resources.airtel_entries
import com.salesapp.resources.airtel_entry_charge
import com.salesapp.resources.airtel_entry_expense
import com.salesapp.resources.airtel_entry_payment
import com.salesapp.resources.airtel_entry_reference
import com.salesapp.resources.airtel_entry_waiting
import com.salesapp.resources.airtel_entry_withdrawal
import com.salesapp.resources.airtel_holder
import com.salesapp.resources.airtel_no_entries
import com.salesapp.resources.airtel_off
import com.salesapp.resources.airtel_only_holder
import com.salesapp.resources.airtel_opening
import com.salesapp.resources.airtel_tick
import com.salesapp.resources.airtel_ticking
import com.salesapp.resources.airtel_waiting_many
import com.salesapp.resources.airtel_waiting_one
import com.salesapp.resources.airtel_withdraw
import com.salesapp.resources.airtel_withdraw_amount
import com.salesapp.resources.airtel_withdraw_charge
import com.salesapp.resources.airtel_withdraw_company
import com.salesapp.resources.airtel_withdraw_help
import com.salesapp.resources.airtel_withdraw_save
import com.salesapp.resources.airtel_withdraw_title
import com.salesapp.resources.cancel
import com.salesapp.ui.cash.BigMoney
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
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
fun WalletDetailPage(
    id: Int,
    onBack: () -> Unit,
    onChange: (Int) -> Unit,
    viewModel: WalletDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(WalletDetailEvent.Refresh)
        onPauseOrDispose {}
    }
    WalletDetailScreen(state, viewModel::onEvent, onBack, onChange)
}

// One wallet: the balance in the app, then the ledger newest first. The holder can tick a payment,
// take cash out and check the app against the real wallet on the phone.
@Composable
fun WalletDetailScreen(
    state: WalletDetailState,
    onEvent: (WalletDetailEvent) -> Unit,
    onBack: () -> Unit,
    onChange: (Int) -> Unit = {},
) {
    val wallet = state.wallet
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(wallet?.let { stringResource(Res.string.airtel_holder, it.holderName) } ?: "", onBack = onBack)
        when {
            wallet != null -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Head(wallet)
                if (state.canAct) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        // Only the holder takes money out: it becomes cash in their own hand.
                        if (state.canTakeOut) {
                            SecondaryButton(
                                stringResource(Res.string.airtel_withdraw),
                                onClick = { onEvent(WalletDetailEvent.OpenForm(WalletForm.Withdraw)) },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        SecondaryButton(
                            stringResource(Res.string.airtel_check),
                            onClick = { onEvent(WalletDetailEvent.OpenForm(WalletForm.Check)) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                    }
                } else {
                    Text(
                        stringResource(Res.string.airtel_only_holder),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                when (state.form) {
                    WalletForm.Withdraw -> if (state.canTakeOut) WithdrawForm(state, wallet, onEvent)
                    WalletForm.Check -> CheckForm(state, onEvent)
                    null -> Unit
                }
                state.actionError?.let { Say(it, error = true) }
                if (state.canSetUp) {
                    SecondaryButton(
                        stringResource(Res.string.airtel_admin_edit),
                        onClick = { onChange(wallet.id) },
                        enabled = !state.busy,
                    )
                }
                Checks(wallet.checks)
                SectionLabel(stringResource(Res.string.airtel_entries))
                if (wallet.entries.isEmpty()) {
                    Say(stringResource(Res.string.airtel_no_entries))
                } else {
                    wallet.entries.forEach { entry -> EntryCard(entry, state, onEvent) }
                }
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(WalletDetailEvent.Retry) })
            else -> LoadingState()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Head(wallet: WalletDetail) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BigMoney(stringResource(Res.string.airtel_balance), wallet.balance)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            wallet.companies.forEach { code -> CompanyDot(code) }
            if (!wallet.active) Badge(stringResource(Res.string.airtel_off), tone = BadgeTone.Off)
            if (wallet.waiting > 0) {
                Badge(
                    if (wallet.waiting == 1) {
                        stringResource(Res.string.airtel_waiting_one)
                    } else {
                        stringResource(Res.string.airtel_waiting_many, wallet.waiting)
                    },
                    tone = BadgeTone.Off,
                )
            }
        }
        Text(
            stringResource(Res.string.airtel_opening, formatMwk(wallet.openingBalance.toLong())),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.caption,
        )
    }
}

// Taking money out: which company's money, how much, and what Airtel charged for it.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WithdrawForm(state: WalletDetailState, wallet: WalletDetail, onEvent: (WalletDetailEvent) -> Unit) {
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.airtel_withdraw_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(Res.string.airtel_withdraw_help),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (wallet.companies.size > 1) {
                SectionLabel(stringResource(Res.string.airtel_withdraw_company))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    wallet.companies.forEach { code ->
                        ChoiceButton(
                            text = code,
                            selected = state.withdrawCompany == code,
                            onClick = { onEvent(WalletDetailEvent.WithdrawCompanyChosen(code)) },
                            enabled = !state.busy,
                        )
                    }
                }
            }
            MwkField(
                label = stringResource(Res.string.airtel_withdraw_amount),
                amount = state.withdrawAmount,
                onAmountChange = { onEvent(WalletDetailEvent.WithdrawAmountChanged(it)) },
            )
            MwkField(
                label = stringResource(Res.string.airtel_withdraw_charge),
                amount = state.withdrawCharge,
                onAmountChange = { onEvent(WalletDetailEvent.WithdrawChargeChanged(it)) },
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    stringResource(Res.string.cancel),
                    onClick = { onEvent(WalletDetailEvent.OpenForm(null)) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    stringResource(Res.string.airtel_withdraw_save),
                    onClick = { onEvent(WalletDetailEvent.Withdraw) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// Checking the app against the balance showing on the Airtel phone.
@Composable
private fun CheckForm(state: WalletDetailState, onEvent: (WalletDetailEvent) -> Unit) {
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(Res.string.airtel_check_title), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
            MwkField(
                label = stringResource(Res.string.airtel_check_actual),
                amount = state.actual,
                onAmountChange = { onEvent(WalletDetailEvent.ActualChanged(it)) },
            )
            Field(
                label = stringResource(Res.string.airtel_check_note),
                value = state.checkNote,
                onValueChange = { onEvent(WalletDetailEvent.CheckNoteChanged(it)) },
                enabled = !state.busy,
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    stringResource(Res.string.cancel),
                    onClick = { onEvent(WalletDetailEvent.OpenForm(null)) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
                PrimaryButton(
                    stringResource(Res.string.airtel_check_save),
                    onClick = { onEvent(WalletDetailEvent.Check) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// What the last checks found, newest first.
@Composable
private fun Checks(checks: List<WalletCheck>) {
    if (checks.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    val sales = MaterialTheme.sales
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.airtel_checks))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                checks.take(3).forEach { check ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(
                                Res.string.airtel_check_row,
                                formatMwk(check.actual.toLong()),
                                formatMwk(check.ledger.toLong()),
                            ),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            if (check.difference == 0.0) {
                                stringResource(Res.string.airtel_check_same)
                            } else {
                                stringResource(Res.string.airtel_check_diff, formatMwk(check.difference.toLong()))
                            },
                            color = if (check.difference == 0.0) sales.good else sales.bad,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (check.note.isNotBlank()) {
                            Text(check.note, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            stringResource(Res.string.airtel_checked_by, check.by, formatIsoDate(localIsoDateFromUtcTimestamp(check.at))),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.caption,
                        )
                    }
                }
            }
        }
    }
}

// One line of the ledger: + for money in, − for money out, and the balance after it.
@Composable
private fun EntryCard(entry: WalletEntry, state: WalletDetailState, onEvent: (WalletDetailEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sales = MaterialTheme.sales
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    entry.description.ifBlank { kindLabel(entry.kind) },
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    (if (entry.isCredit) "+ " else "− ") + formatMwk(entry.amount.toLong()),
                    color = if (entry.isCredit) sales.good else scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanyDot(entry.companyCode)
                Text(kindLabel(entry.kind), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                if (entry.waiting) Badge(stringResource(Res.string.airtel_entry_waiting), tone = BadgeTone.Off)
            }
            entry.reference.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.airtel_entry_reference, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            Text(
                stringResource(Res.string.airtel_balance_after, formatMwk(entry.balanceAfter.toLong())),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.caption,
            )
            Text(
                formatIsoDate(localIsoDateFromUtcTimestamp(entry.createdAt)),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.caption,
            )
            // Only the holder (or admin) can say the money has arrived.
            if (entry.waiting && state.canAct) {
                PrimaryButton(
                    stringResource(if (state.busy) Res.string.airtel_ticking else Res.string.airtel_tick),
                    onClick = { onEvent(WalletDetailEvent.Tick(entry.id)) },
                    enabled = !state.busy,
                )
            }
        }
    }
}

@Composable
private fun kindLabel(kind: String): String = when (kind) {
    "expense" -> stringResource(Res.string.airtel_entry_expense)
    "withdrawal" -> stringResource(Res.string.airtel_entry_withdrawal)
    "charge" -> stringResource(Res.string.airtel_entry_charge)
    else -> stringResource(Res.string.airtel_entry_payment)
}

private val sampleWallet = WalletDetail(
    id = 1,
    holderId = 3,
    holderName = "Mr. Abbas",
    openingBalance = 500_000.0,
    balance = 1_250_000.0,
    companies = listOf("LV", "ZT"),
    waiting = 1,
    entries = listOf(
        WalletEntry(
            id = 9, companyCode = "LV", kind = "payment", credit = 96_000.0, description = "Kanengo Wholesalers · LV-RCT-000008",
            reference = "PP251024.0910.B55", status = "waiting", by = "Chimwemwe Banda", createdAt = "2026-10-24T09:10:00.000Z",
            balanceAfter = 1_250_000.0,
        ),
        WalletEntry(
            id = 8, companyCode = "LV", kind = "withdrawal", debit = 200_000.0, description = "Taken out as cash",
            custody = "with", by = "Mr. Abbas", createdAt = "2026-10-23T17:00:00.000Z", balanceAfter = 1_154_000.0,
        ),
        WalletEntry(
            id = 7, companyCode = "ZT", kind = "expense", debit = 12_000.0, description = "Airtime and data",
            expenseNumber = "ZT-EXP-000002", by = "Mr. Abbas", createdAt = "2026-10-23T11:30:00.000Z", balanceAfter = 1_354_000.0,
        ),
    ),
    checks = listOf(
        WalletCheck(id = 1, actual = 1_250_000.0, ledger = 1_250_000.0, difference = 0.0, by = "Mr. Abbas", at = "2026-10-24T07:00:00.000Z"),
    ),
    canAct = true,
    canTakeOut = true,
)

@Preview
@Composable
private fun WalletDetailPreview() {
    SalesTheme {
        WalletDetailScreen(state = WalletDetailState(loading = false, wallet = sampleWallet), onEvent = {}, onBack = {})
    }
}

@Preview
@Composable
private fun WalletWithdrawPreview() {
    SalesTheme {
        WalletDetailScreen(
            state = WalletDetailState(
                loading = false,
                wallet = sampleWallet,
                form = WalletForm.Withdraw,
                withdrawCompany = "LV",
                withdrawAmount = 200_000,
                withdrawCharge = 2_500,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun WalletCheckPreview() {
    SalesTheme {
        WalletDetailScreen(
            state = WalletDetailState(loading = false, wallet = sampleWallet, form = WalletForm.Check, actual = 1_245_000),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun WalletWatcherPreview() {
    SalesTheme {
        WalletDetailScreen(
            state = WalletDetailState(loading = false, wallet = sampleWallet.copy(canAct = false, canTakeOut = false)),
            onEvent = {},
            onBack = {},
        )
    }
}
