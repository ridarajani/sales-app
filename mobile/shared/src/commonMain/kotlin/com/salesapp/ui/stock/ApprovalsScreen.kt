package com.salesapp.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.core.splitPieces
import com.salesapp.data.PendingAdjustment
import com.salesapp.resources.Res
import com.salesapp.resources.approvals_approve
import com.salesapp.resources.approvals_approve_text
import com.salesapp.resources.approvals_approve_title
import com.salesapp.resources.approvals_approve_yes
import com.salesapp.resources.approvals_asked_by
import com.salesapp.resources.approvals_empty
import com.salesapp.resources.approvals_intro
import com.salesapp.resources.approvals_reject
import com.salesapp.resources.approvals_reject_text
import com.salesapp.resources.approvals_reject_title
import com.salesapp.resources.approvals_take_off
import com.salesapp.resources.approvals_title
import com.salesapp.resources.batch_number
import com.salesapp.resources.number_no_access
import com.salesapp.resources.stock_expires
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.ConfirmDialog
import com.salesapp.ui.components.DangerButton
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.GroupHeader
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import kotlin.math.abs
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ApprovalsPage(
    onBack: () -> Unit,
    viewModel: ApprovalsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(ApprovalsEvent.Refresh)
        onPauseOrDispose {}
    }
    ApprovalsScreen(state, viewModel::onEvent, onBack)
}

// Write-offs the warehouse asked for, grouped by company, oldest first. Admin and Management say yes or no.
@Composable
fun ApprovalsScreen(state: ApprovalsState, onEvent: (ApprovalsEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.approvals_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.number_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        state.actionError?.let { Say(it, error = true, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp)) }
        Text(
            stringResource(Res.string.approvals_intro),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(16.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.rows.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(ApprovalsEvent.Retry) }) }
                state.rows.isEmpty() -> item { EmptyState(stringResource(Res.string.approvals_empty)) }
                else -> {
                    state.groups.forEach { (company, rows) ->
                        item(key = "c-$company") {
                            GroupHeader("", leading = { CompanyDot(company) })
                        }
                        items(rows, key = { "r-${it.id}" }) { row ->
                            ApprovalRow(row, deciding = state.decidingId == row.id, onEvent = onEvent)
                        }
                    }
                }
            }
        }
    }
    val asking = state.asking
    if (asking != null) {
        val (cartons, pieces) = splitPieces(abs(asking.change), asking.piecesPerCarton)
        if (state.askingApprove) {
            ConfirmDialog(
                title = stringResource(Res.string.approvals_approve_title),
                text = stringResource(Res.string.approvals_approve_text, cartons, pieces, asking.name, asking.batchNo),
                confirmLabel = stringResource(Res.string.approvals_approve_yes),
                onConfirm = { onEvent(ApprovalsEvent.Confirm) },
                onDismiss = { onEvent(ApprovalsEvent.Dismiss) },
            )
        } else {
            ConfirmDialog(
                title = stringResource(Res.string.approvals_reject_title),
                text = stringResource(Res.string.approvals_reject_text),
                confirmLabel = stringResource(Res.string.approvals_reject),
                onConfirm = { onEvent(ApprovalsEvent.Confirm) },
                onDismiss = { onEvent(ApprovalsEvent.Dismiss) },
                danger = true,
            )
        }
    }
}

@Composable
private fun ApprovalRow(row: PendingAdjustment, deciding: Boolean, onEvent: (ApprovalsEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val title = listOf(row.name, row.packSize).filter { it.isNotBlank() }.joinToString(" ")
    val (cartons, pieces) = splitPieces(abs(row.change), row.piecesPerCarton)
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.batch_number, row.batchNo) + " · " + stringResource(Res.string.stock_expires, formatIsoDate(row.expiryDate)).replaceFirstChar { it.lowercase() },
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(stringResource(Res.string.approvals_take_off, cartons, pieces), color = scheme.error, style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(Res.string.approvals_asked_by, row.requestedBy, formatIsoDate(localIsoDateFromUtcTimestamp(row.requestedAt))),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (row.note.isNotBlank()) {
                Text(row.note, color = scheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                PrimaryButton(
                    stringResource(Res.string.approvals_approve),
                    modifier = Modifier.weight(1f).height(44.dp),
                    enabled = !deciding,
                    onClick = { onEvent(ApprovalsEvent.AskApprove(row)) },
                )
                DangerButton(
                    stringResource(Res.string.approvals_reject),
                    modifier = Modifier.weight(1f),
                    enabled = !deciding,
                    onClick = { onEvent(ApprovalsEvent.AskReject(row)) },
                )
            }
        }
    }
}

private val sampleApprovals = listOf(
    PendingAdjustment(1, 5, "KG025-2610A", "2027-10-22", 1, "KG025", "Cooking oil", "2 L", "LV", 12, -30, "damaged", "Rain got in.", "Chikondi Banda", "2026-10-21T08:30:00Z"),
    PendingAdjustment(2, 6, "ZF-1", "2027-01-05", 2, "ZF1", "Sugar", "1 kg", "ZF", 24, -24, "damaged", "", "Chikondi Banda", "2026-10-21T09:10:00Z"),
)

@Preview
@Composable
private fun ApprovalsPreview() {
    SalesTheme {
        ApprovalsScreen(ApprovalsState(loading = false, rows = sampleApprovals), onEvent = {}, onBack = {})
    }
}
