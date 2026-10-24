package com.salesapp.ui.cash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.Company
import com.salesapp.data.Expense
import com.salesapp.resources.Res
import com.salesapp.resources.expense_amount
import com.salesapp.resources.expense_cash_note
import com.salesapp.resources.expense_company
import com.salesapp.resources.expense_how
import com.salesapp.resources.expense_mode_airtel
import com.salesapp.resources.expense_mode_cash
import com.salesapp.resources.expense_mode_row
import com.salesapp.resources.expense_photo
import com.salesapp.resources.expense_save
import com.salesapp.resources.expense_saving
import com.salesapp.resources.expense_spent_by
import com.salesapp.resources.expense_title
import com.salesapp.resources.expense_what
import com.salesapp.resources.expense_what_hint
import com.salesapp.resources.expenses_empty
import com.salesapp.resources.expenses_new
import com.salesapp.resources.expenses_no_access
import com.salesapp.resources.expenses_title
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
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

@Composable
fun ExpensesPage(
    onBack: () -> Unit,
    onNew: () -> Unit,
    viewModel: ExpensesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(ExpensesEvent.Refresh)
        onPauseOrDispose {}
    }
    ExpensesScreen(state, viewModel::onEvent, onBack, onNew)
}

// Money spent for a company, newest first.
@Composable
fun ExpensesScreen(
    state: ExpensesState,
    onEvent: (ExpensesEvent) -> Unit,
    onBack: () -> Unit,
    onNew: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.expenses_title), onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.expenses.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(ExpensesEvent.Retry) }) }
                state.expenses.isEmpty() -> item { EmptyState(stringResource(Res.string.expenses_empty)) }
                else -> items(state.expenses, key = { it.id }) { expense -> ExpenseRow(expense) }
            }
        }
        if (state.canAdd) {
            PrimaryButton(stringResource(Res.string.expenses_new), onClick = onNew, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun ExpenseRow(expense: Expense) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(expense.description, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Badge(
                    stringResource(if (expense.mode == "airtel") Res.string.expense_mode_airtel else Res.string.expense_mode_cash),
                    tone = BadgeTone.Role,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanyDot(expense.companyCode)
                Text(expense.number, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            Text(
                formatMwk(expense.amount.toLong()),
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(Res.string.expense_spent_by, expense.spentBy),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.caption,
            )
            Text(
                formatIsoDate(localIsoDateFromUtcTimestamp(expense.createdAt)),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
fun NewExpensePage(
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: NewExpenseViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                NewExpenseEffect.Saved -> onSaved()
            }
        }
    }
    NewExpenseScreen(state, viewModel::onEvent, onBack)
}

// One expense: which company, how much, what for, and how it was paid.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NewExpenseScreen(state: NewExpenseState, onEvent: (NewExpenseEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.expense_title), onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.expenses_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(NewExpenseEvent.Retry) })
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (state.companies.size > 1) {
                        SectionLabel(stringResource(Res.string.expense_company))
                        ChoiceColumn {
                            state.companies.forEach { company ->
                                ChoiceButton(
                                    text = company.name,
                                    selected = state.companyCode == company.code,
                                    onClick = { onEvent(NewExpenseEvent.CompanyChosen(company.code)) },
                                    enabled = !state.busy,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    MwkField(
                        label = stringResource(Res.string.expense_amount),
                        amount = state.amount,
                        onAmountChange = { onEvent(NewExpenseEvent.AmountChanged(it)) },
                    )
                    Field(
                        label = stringResource(Res.string.expense_what),
                        value = state.description,
                        onValueChange = { onEvent(NewExpenseEvent.DescriptionChanged(it)) },
                        placeholder = stringResource(Res.string.expense_what_hint),
                        enabled = !state.busy,
                    )
                    SectionLabel(stringResource(Res.string.expense_how))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(
                            "cash" to stringResource(Res.string.expense_mode_cash),
                            "airtel" to stringResource(Res.string.expense_mode_airtel),
                        ).forEach { (code, label) ->
                            ChoiceButton(
                                text = label,
                                selected = state.mode == code,
                                onClick = { onEvent(NewExpenseEvent.ModeChosen(code)) },
                                enabled = !state.busy,
                            )
                        }
                    }
                    PhotoField(
                        label = stringResource(Res.string.expense_photo),
                        photo = state.photo,
                        cameraAvailable = state.cameraAvailable,
                        busy = state.busy,
                        onTake = { onEvent(NewExpenseEvent.TakePhoto) },
                        onRemove = { onEvent(NewExpenseEvent.RemovePhoto) },
                    )
                    Text(
                        stringResource(Res.string.expense_cash_note),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.actionError?.let { Say(it, error = true) }
                }
                PrimaryButton(
                    stringResource(if (state.busy) Res.string.expense_saving else Res.string.expense_save),
                    onClick = { onEvent(NewExpenseEvent.Save) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private val sampleExpenses = listOf(
    Expense(
        id = 2, number = "LV-EXP-000004", companyCode = "LV", amount = 45_000.0, description = "Fuel for LL 4521",
        mode = "cash", spentBy = "Chimwemwe Banda", custody = "with", createdAt = "2026-10-24T08:00:00.000Z",
    ),
    Expense(
        id = 1, number = "ZT-EXP-000002", companyCode = "ZT", amount = 12_000.0, description = "Airtime and data",
        mode = "airtel", spentBy = "Mr. Abbas", custody = "na", createdAt = "2026-10-23T11:30:00.000Z",
    ),
)

@Preview
@Composable
private fun ExpensesPreview() {
    SalesTheme {
        ExpensesScreen(state = ExpensesState(loading = false, canAdd = true, expenses = sampleExpenses), onEvent = {}, onBack = {}, onNew = {})
    }
}

@Preview
@Composable
private fun ExpensesEmptyPreview() {
    SalesTheme {
        ExpensesScreen(state = ExpensesState(loading = false, canAdd = true), onEvent = {}, onBack = {}, onNew = {})
    }
}

@Preview
@Composable
private fun NewExpensePreview() {
    SalesTheme {
        NewExpenseScreen(
            state = NewExpenseState(
                loading = false,
                companies = listOf(Company("LV", "Lucky Ventures", "F1"), Company("ZT", "Zoona Trading", "F1")),
                companyCode = "LV",
                amount = 45_000,
                description = "Fuel for LL 4521",
                cameraAvailable = true,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
