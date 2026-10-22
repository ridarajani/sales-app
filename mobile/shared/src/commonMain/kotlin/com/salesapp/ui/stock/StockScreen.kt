package com.salesapp.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
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
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.number_no_access
import com.salesapp.resources.stock_count
import com.salesapp.resources.stock_empty_none
import com.salesapp.resources.stock_empty_search
import com.salesapp.resources.stock_qty
import com.salesapp.resources.stock_receive
import com.salesapp.resources.stock_search_hint
import com.salesapp.resources.stock_title
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.GroupHeader
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun StockPage(
    onBack: () -> Unit,
    onOpenProduct: (Int) -> Unit,
    onReceive: () -> Unit,
    onCount: () -> Unit,
    viewModel: StockViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is StockEffect.OpenProduct -> onOpenProduct(effect.productId)
                StockEffect.Receive -> onReceive()
                StockEffect.Count -> onCount()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(StockEvent.Refresh)
        onPauseOrDispose {}
    }
    StockScreen(state, viewModel::onEvent, onBack)
}

// What is on the shelves, grouped by company, with search. Keepers get Receive and Count buttons.
@Composable
fun StockScreen(state: StockState, onEvent: (StockEvent) -> Unit, onBack: () -> Unit) {
    val visible = state.visible
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.stock_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.number_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            SearchField(
                value = state.query,
                onValueChange = { onEvent(StockEvent.QueryChanged(it)) },
                placeholder = stringResource(Res.string.stock_search_hint),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.products.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(StockEvent.Retry) }) }
                visible.isEmpty() -> item {
                    val searching = state.query.isNotBlank()
                    EmptyState(stringResource(if (searching) Res.string.stock_empty_search else Res.string.stock_empty_none))
                }
                else -> {
                    var lastCompany: String? = null
                    visible.forEach { product ->
                        if (product.companyCode != lastCompany) {
                            lastCompany = product.companyCode
                            item(key = "c-${product.companyCode}") {
                                GroupHeader("", leading = { CompanyDot(product.companyCode) })
                            }
                        }
                        item(key = "p-${product.productId}") {
                            StockRow(product, onClick = { onEvent(StockEvent.OpenProduct(product.productId)) })
                        }
                    }
                }
            }
        }
        if (state.canKeep) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PrimaryButton(
                    stringResource(Res.string.stock_receive),
                    onClick = { onEvent(StockEvent.Receive) },
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    stringResource(Res.string.stock_count),
                    onClick = { onEvent(StockEvent.Count) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@Composable
private fun StockRow(product: StockProduct, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(product.title(), color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.stock_qty, product.cartons, product.loosePieces),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            product.nearestExpiry?.let { ExpiryLine(it) }
        }
    }
}

private val sampleStock = listOf(
    StockProduct(1, "LV-KG-400", "LV", "Kerrygold", "Milk powder tin", "400g tin", 24, 250, 250, 10, 10, "2026-12-01", listOf(StockBatch(1, "B1", "2026-12-01", 250, 250, 10, 10))),
    StockProduct(2, "LV-KG-075", "LV", "Kerrygold", "Milk powder sachet", "7.5g x 400", 400, 800, 800, 2, 0, "2020-01-01"),
    StockProduct(3, "ZF-SU-001", "ZF", "Sunrise", "Cooking oil", "2 litre bottle", 6, 60, 60, 10, 0, "2099-01-01"),
)

@Preview
@Composable
private fun StockPreview() {
    SalesTheme {
        StockScreen(StockState(loading = false, products = sampleStock, canKeep = true), onEvent = {}, onBack = {})
    }
}
