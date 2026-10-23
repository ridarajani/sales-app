package com.salesapp.ui.orders

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.OrderCompanyRef
import com.salesapp.data.OrderListItem
import com.salesapp.resources.Res
import com.salesapp.resources.number_no_access
import com.salesapp.resources.order_status_cancelled
import com.salesapp.resources.order_status_done
import com.salesapp.resources.order_status_hold
import com.salesapp.resources.order_status_open
import com.salesapp.resources.orders_list_empty
import com.salesapp.resources.orders_list_new
import com.salesapp.resources.orders_list_no_match
import com.salesapp.resources.orders_list_number_date
import com.salesapp.resources.orders_list_search_hint
import com.salesapp.resources.orders_list_title
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.FilterChips
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun OrdersPage(
    onBack: () -> Unit,
    onOpenOrder: (Int) -> Unit,
    onNewOrder: () -> Unit,
    viewModel: OrdersViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is OrdersEffect.OpenOrder -> onOpenOrder(effect.id)
                OrdersEffect.NewOrder -> onNewOrder()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(OrdersEvent.Refresh)
        onPauseOrDispose {}
    }
    OrdersScreen(state, viewModel::onEvent, onBack)
}

// Booked orders, newest first, with a search box and status chips. Bookers get a "New order" button.
@Composable
fun OrdersScreen(state: OrdersState, onEvent: (OrdersEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.orders_list_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.number_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchField(
                value = state.query,
                onValueChange = { onEvent(OrdersEvent.QueryChanged(it)) },
                placeholder = stringResource(Res.string.orders_list_search_hint),
            )
            FilterChips(
                options = listOf(
                    "open" to stringResource(Res.string.order_status_open),
                    "hold" to stringResource(Res.string.order_status_hold),
                    "done" to stringResource(Res.string.order_status_done),
                    "cancelled" to stringResource(Res.string.order_status_cancelled),
                ),
                selected = state.status,
                onSelect = { onEvent(OrdersEvent.StatusChosen(it)) },
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.orders.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(OrdersEvent.Retry) }) }
                state.orders.isEmpty() -> item {
                    EmptyState(stringResource(if (state.filtering) Res.string.orders_list_no_match else Res.string.orders_list_empty))
                }
                else -> items(state.orders, key = { it.id }) { order ->
                    OrderRow(order, showShort = state.seesStock, onClick = { onEvent(OrdersEvent.OpenOrder(order.id)) })
                }
            }
        }
        if (state.canBook) {
            PrimaryButton(
                stringResource(Res.string.orders_list_new),
                onClick = { onEvent(OrdersEvent.NewOrder) },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun OrderRow(order: OrderListItem, showShort: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    order.customerName,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                OrderStatusBadge(order.status, modifier = Modifier.padding(start = 8.dp))
            }
            val date = if (order.createdAt.isBlank()) "" else formatIsoDate(localIsoDateFromUtcTimestamp(order.createdAt))
            Text(
                if (date.isBlank()) order.number else stringResource(Res.string.orders_list_number_date, order.number, date),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (order.companies.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    order.companies.forEach { CompanyDot(it.code) }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatMwk(order.total),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    modifier = Modifier.weight(1f),
                )
                if (showShort && order.shortPieces > 0) {
                    ShortBadge(modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

private val sampleOrders = listOf(
    OrderListItem(
        id = 3, number = "SO-000123", status = "open", createdAt = "2026-10-23T07:45:00Z",
        customerId = 11, customerName = "Chikondi Grocery", area = "Area 25",
        companies = listOf(OrderCompanyRef("LV", "LPO-4471"), OrderCompanyRef("ZT")), shortPieces = 18, total = 1_254_000,
    ),
    OrderListItem(
        id = 2, number = "SO-000122", status = "hold", createdAt = "2026-10-22T13:10:00Z",
        customerId = 12, customerName = "Mwayi Supermarket", area = "Limbe",
        companies = listOf(OrderCompanyRef("ZF")), total = 486_500,
    ),
    OrderListItem(
        id = 1, number = "SO-000121", status = "cancelled", invoiceType = "plain", createdAt = "2026-10-21T09:00:00Z",
        customerId = 13, customerName = "Tiyamike Shop", area = "Ndirande",
        companies = listOf(OrderCompanyRef("GL")), total = 72_000,
    ),
)

@Preview
@Composable
private fun OrdersPreview() {
    SalesTheme {
        OrdersScreen(
            state = OrdersState(loading = false, seesStock = true, canBook = true, orders = sampleOrders),
            onEvent = {},
            onBack = {},
        )
    }
}
