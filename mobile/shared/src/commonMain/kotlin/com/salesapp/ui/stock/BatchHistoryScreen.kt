package com.salesapp.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.core.splitPieces
import com.salesapp.data.StockBatch
import com.salesapp.data.StockMovement
import com.salesapp.data.StockProduct
import com.salesapp.resources.Res
import com.salesapp.resources.batch_adjusted
import com.salesapp.resources.batch_change_minus
import com.salesapp.resources.batch_change_plus
import com.salesapp.resources.batch_counted
import com.salesapp.resources.batch_doc_who_when
import com.salesapp.resources.batch_gone
import com.salesapp.resources.batch_history
import com.salesapp.resources.batch_history_empty
import com.salesapp.resources.batch_in_stock
import com.salesapp.resources.batch_reason_damaged
import com.salesapp.resources.batch_reason_expired
import com.salesapp.resources.batch_reason_found
import com.salesapp.resources.batch_reason_lost
import com.salesapp.resources.batch_reason_other
import com.salesapp.resources.batch_received
import com.salesapp.resources.batch_title
import com.salesapp.resources.batch_who_when
import com.salesapp.resources.number_no_access
import com.salesapp.resources.stock_adjust
import com.salesapp.resources.stock_title
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.sales
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.math.abs

@Composable
fun BatchHistoryPage(
    batchId: Int,
    onBack: () -> Unit,
    onAdjust: (Int) -> Unit,
    viewModel: BatchHistoryViewModel = koinViewModel { parametersOf(batchId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BatchHistoryEffect.Adjust -> onAdjust(effect.batchId)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(BatchHistoryEvent.Refresh)
        onPauseOrDispose {}
    }
    BatchHistoryScreen(state, viewModel::onEvent, onBack)
}

// One batch: what is left of it and everything that happened to it, newest first.
@Composable
fun BatchHistoryScreen(state: BatchHistoryState, onEvent: (BatchHistoryEvent) -> Unit, onBack: () -> Unit) {
    val product = state.product
    val batch = state.batch
    val title = if (batch == null) stringResource(Res.string.stock_title) else stringResource(Res.string.batch_title, batch.batchNo)
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title, onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.number_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && batch == null -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(BatchHistoryEvent.Retry) }) }
                product == null || batch == null -> item { EmptyState(stringResource(Res.string.batch_gone)) }
                else -> {
                    item(key = "header") { BatchHeader(product, batch) }
                    item(key = "label") { SectionLabel(stringResource(Res.string.batch_history)) }
                    if (state.movements.isEmpty()) {
                        item(key = "empty") { EmptyState(stringResource(Res.string.batch_history_empty)) }
                    } else {
                        items(state.movements, key = { it.id }) { movement ->
                            MovementRow(movement, product.piecesPerCarton)
                        }
                    }
                }
            }
        }
        if (state.canKeep && batch != null) {
            PrimaryButton(
                stringResource(Res.string.stock_adjust),
                onClick = { onEvent(BatchHistoryEvent.Adjust) },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun BatchHeader(product: StockProduct, batch: StockBatch) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(product.title(), color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            ExpiryLine(batch.expiryDate)
            Text(
                stringResource(Res.string.batch_in_stock, batch.cartons, batch.loosePieces),
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun MovementRow(movement: StockMovement, piecesPerCarton: Int) {
    val scheme = MaterialTheme.colorScheme
    val (cartons, loose) = splitPieces(abs(movement.change), piecesPerCarton)
    val what = when (movement.kind) {
        "received" -> stringResource(Res.string.batch_received)
        "counted" -> stringResource(Res.string.batch_counted)
        else -> stringResource(Res.string.batch_adjusted, reasonWord(movement.reason))
    }
    val plus = movement.change >= 0
    val date = formatIsoDate(localIsoDateFromUtcTimestamp(movement.createdAt))
    val who = movement.documentNumber?.let { stringResource(Res.string.batch_doc_who_when, it, movement.userName, date) }
        ?: stringResource(Res.string.batch_who_when, movement.userName, date)
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(what, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
            Text(
                stringResource(if (plus) Res.string.batch_change_plus else Res.string.batch_change_minus, cartons, loose),
                color = if (plus) MaterialTheme.sales.good else scheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(who, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (movement.note.isNotBlank()) {
                Text(movement.note, color = scheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun reasonWord(reason: String?): String = stringResource(
    when (reason) {
        "damaged" -> Res.string.batch_reason_damaged
        "expired" -> Res.string.batch_reason_expired
        "lost" -> Res.string.batch_reason_lost
        "found" -> Res.string.batch_reason_found
        else -> Res.string.batch_reason_other
    },
)

private val sampleBatchProduct = StockProduct(1, "LV-KG-400", "LV", "Kerrygold", "Milk powder tin", "400g tin", 24, 250, 250, 10, 10, "2026-12-01")
private val sampleBatch = StockBatch(1, "B1", "2026-12-01", 250, 250, 10, 10)
private val sampleMovements = listOf(
    StockMovement(3, -5, "adjusted", "damaged", "Wet cartons", "Banda Phiri", "2026-10-20T09:00:00Z"),
    StockMovement(2, 12, "counted", null, "", "Banda Phiri", "2026-10-10T09:00:00Z"),
    StockMovement(1, 480, "received", null, "", "Banda Phiri", "2026-10-01T09:00:00Z", "GRN-0001"),
)

@Preview
@Composable
private fun BatchHistoryPreview() {
    SalesTheme {
        BatchHistoryScreen(
            BatchHistoryState(loading = false, product = sampleBatchProduct, batch = sampleBatch, movements = sampleMovements, canKeep = true),
            onEvent = {},
            onBack = {},
        )
    }
}
