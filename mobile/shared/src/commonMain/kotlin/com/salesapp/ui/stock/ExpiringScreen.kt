package com.salesapp.ui.stock

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.data.ShortDatedBatch
import com.salesapp.resources.Res
import com.salesapp.resources.batch_number
import com.salesapp.resources.expiring_days_left
import com.salesapp.resources.expiring_empty
import com.salesapp.resources.expiring_intro
import com.salesapp.resources.expiring_one_day_left
import com.salesapp.resources.expiring_title
import com.salesapp.resources.number_no_access
import com.salesapp.resources.expiring_today
import com.salesapp.resources.stock_expires
import com.salesapp.resources.stock_pill_expired
import com.salesapp.resources.stock_qty
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.GroupHeader
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ExpiringPage(
    onBack: () -> Unit,
    onOpenBatch: (Int) -> Unit,
    viewModel: ExpiringViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ExpiringEffect.OpenBatch -> onOpenBatch(effect.batchId)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(ExpiringEvent.Refresh)
        onPauseOrDispose {}
    }
    ExpiringScreen(state, viewModel::onEvent, onBack)
}

// Batches that expire within 90 days, grouped by company, soonest first.
@Composable
fun ExpiringScreen(state: ExpiringState, onEvent: (ExpiringEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.expiring_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.number_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Text(
            stringResource(Res.string.expiring_intro),
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
                state.loading && state.batches.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(ExpiringEvent.Retry) }) }
                state.batches.isEmpty() -> item { EmptyState(stringResource(Res.string.expiring_empty)) }
                else -> {
                    state.groups.forEach { (company, rows) ->
                        item(key = "c-$company") {
                            GroupHeader("", leading = { CompanyDot(company) })
                        }
                        items(rows, key = { "b-${it.id}" }) { batch ->
                            ExpiringRow(batch, onClick = { onEvent(ExpiringEvent.OpenBatch(batch.id)) })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpiringRow(batch: ShortDatedBatch, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val title = listOf(batch.name, batch.packSize).filter { it.isNotBlank() }.joinToString(" ")
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(Res.string.batch_number, batch.batchNo), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.stock_expires, formatIsoDate(batch.expiryDate)),
                    color = if (batch.expired) scheme.error else scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (batch.expired) {
                    Badge(stringResource(Res.string.stock_pill_expired), tone = BadgeTone.Off)
                } else {
                    val left = when (batch.daysLeft) {
                        0 -> stringResource(Res.string.expiring_today)
                        1 -> stringResource(Res.string.expiring_one_day_left)
                        else -> stringResource(Res.string.expiring_days_left, batch.daysLeft)
                    }
                    Badge(left, tone = BadgeTone.Role)
                }
            }
            Text(
                stringResource(Res.string.stock_qty, batch.cartons, batch.loosePieces),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private val sampleExpiring = listOf(
    ShortDatedBatch(1, 1, "LV", "B1", "2026-09-01", 100, 24, "LV-KG-400", "Milk powder tin", "400g tin", -51, true, 100, 4, 4),
    ShortDatedBatch(2, 2, "LV", "B7", "2026-11-20", 48, 24, "LV-KG-075", "Milk powder sachet", "7.5g x 400", 29, false, 48, 2, 0),
    ShortDatedBatch(3, 3, "ZF", "C2", "2026-10-22", 6, 6, "ZF-SU-001", "Cooking oil", "2 litre bottle", 0, false, 6, 1, 0),
)

@Preview
@Composable
private fun ExpiringPreview() {
    SalesTheme {
        ExpiringScreen(ExpiringState(loading = false, batches = sampleExpiring), onEvent = {}, onBack = {})
    }
}
