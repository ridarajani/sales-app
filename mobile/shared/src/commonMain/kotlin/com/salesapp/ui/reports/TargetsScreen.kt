package com.salesapp.ui.reports

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatMonth
import com.salesapp.data.TargetRow
import com.salesapp.resources.Res
import com.salesapp.resources.targets_amount
import com.salesapp.resources.targets_help
import com.salesapp.resources.targets_month_back
import com.salesapp.resources.targets_month_next
import com.salesapp.resources.targets_no_access
import com.salesapp.resources.targets_none
import com.salesapp.resources.targets_saved
import com.salesapp.resources.targets_title
import com.salesapp.resources.form_save
import com.salesapp.ui.components.Avatar
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun TargetsPage(onBack: () -> Unit, viewModel: TargetsViewModel = koinViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TargetsScreen(state, viewModel::onEvent, onBack)
}

// One month at a time: every salesman this person may set, with the target they are given.
@Composable
fun TargetsScreen(state: TargetsState, onEvent: (TargetsEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.targets_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.targets_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Month(state, onEvent)
        Text(
            stringResource(Res.string.targets_help),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        state.actionError?.let { Say(it, error = true, modifier = Modifier.padding(16.dp)) }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.rows.isEmpty() -> item { LoadingState() }
                state.error != null && state.rows.isEmpty() -> item {
                    ErrorState(state.error, onRetry = { onEvent(TargetsEvent.Retry) })
                }
                state.none -> item { EmptyState(stringResource(Res.string.targets_none)) }
                else -> items(state.rows, key = { it.salesmanId }) { row ->
                    TargetCard(
                        row = row,
                        amount = state.amounts[row.salesmanId],
                        saving = row.salesmanId in state.saving,
                        saved = row.salesmanId in state.saved,
                        onAmount = { onEvent(TargetsEvent.AmountChanged(row.salesmanId, it)) },
                        onSave = { onEvent(TargetsEvent.Save(row.salesmanId)) },
                    )
                }
            }
        }
    }
}

// The month on screen, with a step either way.
@Composable
private fun Month(state: TargetsState, onEvent: (TargetsEvent) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SecondaryButton(
            stringResource(Res.string.targets_month_back),
            onClick = { onEvent(TargetsEvent.MonthBack) },
            modifier = Modifier.weight(1f),
        )
        Text(
            formatMonth(state.month),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            textAlign = TextAlign.Center,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(
            stringResource(Res.string.targets_month_next),
            onClick = { onEvent(TargetsEvent.MonthNext) },
            modifier = Modifier.weight(1f),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TargetCard(
    row: TargetRow,
    amount: Long?,
    saving: Boolean,
    saved: Boolean,
    onAmount: (Long?) -> Unit,
    onSave: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ListCard(onClick = null) {
            Avatar(row.name)
            Column(
                modifier = Modifier.weight(1f).padding(start = 14.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    row.name,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    row.companies.forEach { CompanyDot(it) }
                }
                // Quietly, with no dialog: the target is in.
                if (saved) {
                    Text(stringResource(Res.string.targets_saved), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
            MwkField(
                label = stringResource(Res.string.targets_amount),
                amount = amount,
                onAmountChange = onAmount,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                stringResource(Res.string.form_save),
                onClick = onSave,
                enabled = !saving,
                modifier = Modifier.weight(0.5f),
            )
        }
    }
}

private val sampleTargets = listOf(
    TargetRow(salesmanId = 4, name = "Chimwemwe Banda", companies = listOf("LV", "ZT"), amount = 8_000_000.0),
    TargetRow(salesmanId = 5, name = "Grace Mwale", companies = listOf("ZF")),
)

@Preview
@Composable
private fun TargetsPreview() {
    SalesTheme {
        TargetsScreen(
            TargetsState(
                loading = false,
                month = "2026-10",
                rows = sampleTargets,
                amounts = mapOf(4 to 8_000_000L),
                saved = setOf(4),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun TargetsEmptyPreview() {
    SalesTheme { TargetsScreen(TargetsState(loading = false, month = "2026-11"), onEvent = {}, onBack = {}) }
}
