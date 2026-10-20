package com.salesapp.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.Product
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.catalog_off
import com.salesapp.resources.empty_nothing_matches
import com.salesapp.resources.products_add
import com.salesapp.resources.products_empty
import com.salesapp.resources.products_hide_off
import com.salesapp.resources.products_pack_line
import com.salesapp.resources.products_search_hint
import com.salesapp.resources.products_show_off
import com.salesapp.resources.products_title
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.FilterChips
import com.salesapp.ui.components.GroupHeader
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ProductsPage(
    onBack: () -> Unit,
    onOpenProduct: (Int) -> Unit,
    onAddProduct: () -> Unit,
    viewModel: ProductsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is ProductsEffect.OpenProduct -> onOpenProduct(effect.id)
                ProductsEffect.AddProduct -> onAddProduct()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(ProductsEvent.Refresh)
        onPauseOrDispose {}
    }
    ProductsScreen(state, viewModel::onEvent, onBack)
}

// Products grouped by company and brand, with search and an "Add a product" button.
@Composable
fun ProductsScreen(state: ProductsState, onEvent: (ProductsEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.products_title), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchField(
                value = state.query,
                onValueChange = { onEvent(ProductsEvent.QueryChanged(it)) },
                placeholder = stringResource(Res.string.products_search_hint),
            )
            if (state.companies.size > 1) {
                FilterChips(
                    options = state.companies.map { it to companyName(it) },
                    selected = state.company,
                    onSelect = { onEvent(ProductsEvent.CompanyChosen(it)) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.products.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(ProductsEvent.Retry) }) }
                state.products.isEmpty() -> item {
                    val searching = state.query.isNotBlank() || state.company != null
                    EmptyState(stringResource(if (searching) Res.string.empty_nothing_matches else Res.string.products_empty))
                }
                else -> {
                    var lastCompany: String? = null
                    var lastBrand: String? = null
                    state.products.forEach { product ->
                        if (product.companyCode != lastCompany) {
                            lastCompany = product.companyCode
                            lastBrand = null
                            item(key = "c-${product.companyCode}") {
                                GroupHeader("", leading = { CompanyDot(product.companyCode) })
                            }
                        }
                        if (product.brand != lastBrand) {
                            lastBrand = product.brand
                            if (product.brand.isNotBlank()) {
                                item(key = "b-${product.companyCode}-${product.brand}") {
                                    Text(
                                        product.brand,
                                        color = scheme.onSurface,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        modifier = Modifier.padding(top = 8.dp),
                                    )
                                }
                            }
                        }
                        item(key = "p-${product.id}") {
                            ProductRow(product, canEdit = state.canEdit, onClick = { onEvent(ProductsEvent.OpenProduct(product.id)) })
                        }
                    }
                }
            }
            item {
                TextButton(
                    onClick = { onEvent(ProductsEvent.ToggleInactive) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(if (state.includeInactive) Res.string.products_hide_off else Res.string.products_show_off),
                        fontSize = 15.sp,
                    )
                }
            }
        }
        if (state.canEdit) {
            PrimaryButton(
                stringResource(Res.string.products_add),
                onClick = { onEvent(ProductsEvent.AddProduct) },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ProductRow(product: Product, canEdit: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = if (canEdit) onClick else null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(product.name, color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(Res.string.products_pack_line, product.packSize, product.piecesPerCarton.toString()),
                color = scheme.onSurfaceVariant,
                fontSize = 14.sp,
            )
            Text(product.code, color = scheme.onSurfaceVariant, fontSize = 12.sp)
        }
        if (!product.active) {
            Badge(stringResource(Res.string.catalog_off), tone = BadgeTone.Off, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

private val sampleProducts = listOf(
    Product(1, "LV-KG-400", "LV", "Kerrygold", "Milk powder tin", "400g tin", 24),
    Product(2, "LV-KG-075", "LV", "Kerrygold", "Milk powder sachet", "7.5g x 400", 400, active = false),
    Product(3, "ZF-SU-001", "ZF", "Sunrise", "Cooking oil", "2 litre bottle", 6),
)

@Preview
@Composable
private fun ProductsPreview() {
    SalesTheme {
        ProductsScreen(
            state = ProductsState(loading = false, products = sampleProducts, companies = listOf("LV", "ZF"), canEdit = true),
            onEvent = {},
            onBack = {},
        )
    }
}
