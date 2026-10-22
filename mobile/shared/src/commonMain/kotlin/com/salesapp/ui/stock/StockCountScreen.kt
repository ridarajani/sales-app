package com.salesapp.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.splitPieces
import com.salesapp.data.CountDifference
import com.salesapp.data.CountResult
import com.salesapp.data.StockBatch
import com.salesapp.data.StockProduct
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.count_batches_counted
import com.salesapp.resources.count_diff_counted
import com.salesapp.resources.count_diff_minus
import com.salesapp.resources.count_diff_plus
import com.salesapp.resources.count_diff_system
import com.salesapp.resources.count_diff_title
import com.salesapp.resources.count_differences
import com.salesapp.resources.count_done_title
import com.salesapp.resources.count_empty
import com.salesapp.resources.count_help
import com.salesapp.resources.count_matched
import com.salesapp.resources.count_save
import com.salesapp.resources.count_system
import com.salesapp.resources.count_title
import com.salesapp.resources.number_batch_line
import com.salesapp.resources.number_cartons
import com.salesapp.resources.number_company
import com.salesapp.resources.number_done
import com.salesapp.resources.number_no_access
import com.salesapp.resources.number_pieces
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.sales
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun StockCountPage(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: StockCountViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { onDone() }
    }
    StockCountScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun StockCountScreen(state: StockCountState, onEvent: (StockCountEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        when {
            !state.allowed -> {
                TopBar(stringResource(Res.string.count_title), onBack = onBack)
                Say(stringResource(Res.string.number_no_access), modifier = Modifier.padding(16.dp))
            }
            state.result != null -> CountDone(state, state.result, onEvent)
            else -> CountForm(state, onEvent, onBack)
        }
    }
}

@Composable
private fun ColumnScope.CountForm(state: StockCountState, onEvent: (StockCountEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    TopBar(stringResource(Res.string.count_title), onBack = onBack)
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SectionLabel(stringResource(Res.string.number_company))
        ChoiceColumn {
            state.companies.forEach { code ->
                ChoiceButton(
                    companyName(code),
                    selected = state.company == code,
                    onClick = { onEvent(StockCountEvent.CompanyChosen(code)) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.busy,
                )
            }
        }
    }
    when {
        state.company == null -> Column(modifier = Modifier.weight(1f)) {}
        state.loading -> LoadingState()
        state.loadError != null -> ErrorState(state.loadError, onRetry = { onEvent(StockCountEvent.Retry) })
        state.rows.isEmpty() -> EmptyState(stringResource(Res.string.count_empty))
        else -> {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item(key = "help") {
                    Text(stringResource(Res.string.count_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
                }
                items(state.rows, key = { it.batch.id }) { row ->
                    val typed = state.counted[row.batch.id]
                    ListCard(onClick = null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    formProductTitle(row.product.brand, row.product.name, row.product.packSize),
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Text(
                                    stringResource(Res.string.number_batch_line, row.batch.batchNo, formatIsoDate(row.batch.expiryDate)),
                                    color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    stringResource(Res.string.count_system, row.batch.cartons.toString(), row.batch.loosePieces.toString()),
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                NumberField(
                                    stringResource(Res.string.number_cartons),
                                    typed?.first,
                                    { onEvent(StockCountEvent.Cartons(row.batch.id, it)) },
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.busy,
                                )
                                NumberField(
                                    stringResource(Res.string.number_pieces),
                                    typed?.second,
                                    { onEvent(StockCountEvent.Pieces(row.batch.id, it)) },
                                    modifier = Modifier.weight(1f),
                                    enabled = !state.busy,
                                )
                            }
                        }
                    }
                }
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
            PrimaryButton(
                stringResource(Res.string.count_save),
                onClick = { onEvent(StockCountEvent.Save) },
                enabled = state.company != null && state.hasCounts,
            )
        }
    }
}

@Composable
private fun ColumnScope.CountDone(state: StockCountState, result: CountResult, onEvent: (StockCountEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    TopBar(stringResource(Res.string.count_done_title))
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            result.number,
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(Res.string.count_batches_counted, result.linesCounted.toString()),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        if (result.differences.isEmpty()) {
            Say(stringResource(Res.string.count_matched))
        } else {
            Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(Res.string.count_differences))
                result.differences.forEach { diff ->
                    val row = state.rows.firstOrNull { it.batch.id == diff.batchId }
                    DifferenceCard(diff, row?.product)
                }
            }
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        PrimaryButton(stringResource(Res.string.number_done), onClick = { onEvent(StockCountEvent.Finish) })
    }
}

@Composable
private fun DifferenceCard(diff: CountDifference, product: StockProduct?) {
    val scheme = MaterialTheme.colorScheme
    val per = product?.piecesPerCarton ?: 1
    val (beforeC, beforeP) = splitPieces(diff.before, per)
    val (countedC, countedP) = splitPieces(diff.counted, per)
    val (changeC, changeP) = splitPieces(kotlin.math.abs(diff.change), per)
    val title = if (product != null) formProductTitle(product.brand, product.name, product.packSize) else ""
    val gained = diff.change > 0
    ListCard(onClick = null) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(Res.string.count_diff_title, title, diff.batchNo).trim(),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                stringResource(Res.string.count_diff_system, beforeC.toString(), beforeP.toString()),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(Res.string.count_diff_counted, countedC.toString(), countedP.toString()),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                stringResource(
                    if (gained) Res.string.count_diff_plus else Res.string.count_diff_minus,
                    changeC.toString(),
                    changeP.toString(),
                ),
                color = if (gained) MaterialTheme.sales.good else scheme.error,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
}

private val previewProduct = StockProduct(1, "KG025", "LV", "Kings", "Cooking oil", "2 L", 12)
private val previewBatch = StockBatch(5, "KG025-2610A", "2027-10-22", cartons = 10, loosePieces = 3)

@Preview
@Composable
private fun StockCountPreview() {
    SalesTheme {
        StockCountScreen(
            state = StockCountState(
                companies = listOf("LV", "ZT"),
                company = "LV",
                rows = listOf(CountRow(previewProduct, previewBatch)),
                counted = mapOf(5 to (10 to null)),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun StockCountDonePreview() {
    SalesTheme {
        StockCountScreen(
            state = StockCountState(
                company = "LV",
                rows = listOf(CountRow(previewProduct, previewBatch)),
                result = CountResult(
                    id = 1,
                    number = "LV-CNT-000001",
                    linesCounted = 1,
                    differences = listOf(CountDifference(5, "KG025-2610A", before = 123, counted = 120, change = -3)),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
