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
import com.salesapp.data.StockBatch
import com.salesapp.data.StockProduct
import com.salesapp.resources.Res
import com.salesapp.resources.batch_number
import com.salesapp.resources.number_no_access
import com.salesapp.resources.stock_product_batches
import com.salesapp.resources.stock_product_gone
import com.salesapp.resources.stock_product_per_carton
import com.salesapp.resources.stock_product_total
import com.salesapp.resources.stock_qty
import com.salesapp.resources.stock_title
import com.salesapp.resources.stock_transit_help
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun StockProductPage(
    productId: Int,
    onBack: () -> Unit,
    onOpenBatch: (Int) -> Unit,
    viewModel: StockProductViewModel = koinViewModel { parametersOf(productId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is StockProductEffect.OpenBatch -> onOpenBatch(effect.batchId)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(StockProductEvent.Refresh)
        onPauseOrDispose {}
    }
    StockProductScreen(state, viewModel::onEvent, onBack)
}

// One product: its details and every batch, oldest expiry first.
@Composable
fun StockProductScreen(state: StockProductState, onEvent: (StockProductEvent) -> Unit, onBack: () -> Unit) {
    val product = state.product
    val title = if (product == null) stringResource(Res.string.stock_title) else listOf(product.brand, product.name).filter { it.isNotBlank() }.joinToString(" ")
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
                state.loading && product == null -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(StockProductEvent.Retry) }) }
                product == null -> item { EmptyState(stringResource(Res.string.stock_product_gone)) }
                else -> {
                    item(key = "header") { ProductHeader(product) }
                    item(key = "label") { SectionLabel(stringResource(Res.string.stock_product_batches)) }
                    items(product.batches, key = { it.id }) { batch ->
                        BatchRow(batch, product.piecesPerCarton, onClick = { onEvent(StockProductEvent.OpenBatch(batch.id)) })
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductHeader(product: StockProduct) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (product.packSize.isNotBlank()) {
                Text(product.packSize, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
            }
            CompanyDot(product.companyCode)
            Text(
                stringResource(Res.string.stock_product_total, product.cartons, product.loosePieces),
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
            )
            TransitLines(product.inTransit, product.free, product.piecesPerCarton)
            Text(
                stringResource(Res.string.stock_product_per_carton, product.piecesPerCarton),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(Res.string.stock_transit_help),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun BatchRow(batch: StockBatch, piecesPerCarton: Int, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(Res.string.batch_number, batch.batchNo), color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            ExpiryLine(batch.expiryDate)
            Text(
                stringResource(Res.string.stock_qty, batch.cartons, batch.loosePieces),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            TransitLines(batch.inTransit, batch.free, piecesPerCarton)
        }
    }
}

private val sampleProduct = StockProduct(
    1, "LV-KG-400", "LV", "Kerrygold", "Milk powder tin", "400g tin", 24, 250, 250, 10, 10, "2026-12-01",
    listOf(
        StockBatch(1, "B1", "2026-12-01", 100, 100, 4, 4, inTransit = 48, reserved = 30, free = 22),
        StockBatch(2, "B2", "2099-06-01", 150, 150, 6, 6, reserved = 30, free = 120),
    ),
    inTransit = 48,
    reserved = 60,
    free = 142,
)

@Preview
@Composable
private fun StockProductPreview() {
    SalesTheme {
        StockProductScreen(StockProductState(loading = false, product = sampleProduct), onEvent = {}, onBack = {})
    }
}
