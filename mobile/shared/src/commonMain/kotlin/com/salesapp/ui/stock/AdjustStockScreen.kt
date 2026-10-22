package com.salesapp.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.data.StockBatch
import com.salesapp.data.StockProduct
import com.salesapp.resources.Res
import com.salesapp.resources.adjust_add
import com.salesapp.resources.adjust_adds
import com.salesapp.resources.adjust_damaged
import com.salesapp.resources.adjust_direction
import com.salesapp.resources.adjust_expired
import com.salesapp.resources.adjust_found
import com.salesapp.resources.adjust_how_much
import com.salesapp.resources.adjust_in_stock
import com.salesapp.resources.adjust_lost
import com.salesapp.resources.adjust_other
import com.salesapp.resources.adjust_save
import com.salesapp.resources.adjust_take_off
import com.salesapp.resources.adjust_takes_off
import com.salesapp.resources.adjust_title
import com.salesapp.resources.adjust_what_happened
import com.salesapp.resources.number_batch_line
import com.salesapp.resources.number_cartons
import com.salesapp.resources.batch_gone
import com.salesapp.resources.number_no_access
import com.salesapp.resources.number_pieces
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MultilineField
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun AdjustStockPage(
    batchId: Int,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: AdjustStockViewModel = koinViewModel { parametersOf(batchId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { onDone() }
    }
    AdjustStockScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun AdjustStockScreen(state: AdjustStockState, onEvent: (AdjustStockEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopBar(stringResource(Res.string.adjust_title), onBack = onBack)
        val product = state.product
        val batch = state.batch
        when {
            !state.allowed -> Say(stringResource(Res.string.number_no_access), modifier = Modifier.padding(16.dp))
            state.loading -> LoadingState()
            state.loadError != null -> ErrorState(state.loadError, onRetry = { onEvent(AdjustStockEvent.Retry) })
            product == null || batch == null -> EmptyState(stringResource(Res.string.batch_gone))
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    ListCard(onClick = null) {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(formProductTitle(product.brand, product.name, product.packSize), style = MaterialTheme.typography.titleSmall)
                            Text(
                                stringResource(Res.string.number_batch_line, batch.batchNo, formatIsoDate(batch.expiryDate)),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                stringResource(Res.string.adjust_in_stock, batch.cartons.toString(), batch.loosePieces.toString()),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(stringResource(Res.string.adjust_what_happened))
                        ChoiceColumn {
                            listOf(
                                "damaged" to Res.string.adjust_damaged,
                                "expired" to Res.string.adjust_expired,
                                "lost" to Res.string.adjust_lost,
                                "found" to Res.string.adjust_found,
                                "other" to Res.string.adjust_other,
                            ).forEach { (code, label) ->
                                ChoiceButton(
                                    stringResource(label),
                                    selected = state.reason == code,
                                    onClick = { onEvent(AdjustStockEvent.Reason(code)) },
                                    modifier = Modifier.fillMaxWidth(),
                                    enabled = !state.busy,
                                )
                            }
                        }
                        when (state.reason) {
                            "damaged", "expired", "lost" -> Text(
                                stringResource(Res.string.adjust_takes_off),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            "found" -> Text(
                                stringResource(Res.string.adjust_adds),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (state.reason == "other") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel(stringResource(Res.string.adjust_direction))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                ChoiceButton(
                                    stringResource(Res.string.adjust_add),
                                    selected = state.direction == "add",
                                    onClick = { onEvent(AdjustStockEvent.Direction("add")) },
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.busy,
                                )
                                ChoiceButton(
                                    stringResource(Res.string.adjust_take_off),
                                    selected = state.direction == "remove",
                                    onClick = { onEvent(AdjustStockEvent.Direction("remove")) },
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.busy,
                                )
                            }
                        }
                        MultilineField(
                            stringResource(Res.string.adjust_what_happened),
                            state.note,
                            { onEvent(AdjustStockEvent.Note(it)) },
                            enabled = !state.busy,
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(stringResource(Res.string.adjust_how_much))
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            NumberField(
                                stringResource(Res.string.number_cartons),
                                state.cartons,
                                { onEvent(AdjustStockEvent.Cartons(it)) },
                                modifier = Modifier.weight(1f),
                                enabled = !state.busy,
                            )
                            NumberField(
                                stringResource(Res.string.number_pieces),
                                state.pieces,
                                { onEvent(AdjustStockEvent.Pieces(it)) },
                                modifier = Modifier.weight(1f),
                                enabled = !state.busy,
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.error?.let { Say(it, error = true) }
                    if (state.busy) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator(color = scheme.primary)
                        }
                    } else {
                        PrimaryButton(stringResource(Res.string.adjust_save), onClick = { onEvent(AdjustStockEvent.Save) })
                    }
                }
            }
        }
    }
}

@Preview
@Composable
private fun AdjustStockPreview() {
    SalesTheme {
        AdjustStockScreen(
            state = AdjustStockState(
                loading = false,
                product = StockProduct(1, "KG025", "LV", "Kings", "Cooking oil", "2 L", 12),
                batch = StockBatch(5, "KG025-2610A", "2027-10-22", cartons = 10, loosePieces = 3),
                reason = "other",
                direction = "remove",
                cartons = 1,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
