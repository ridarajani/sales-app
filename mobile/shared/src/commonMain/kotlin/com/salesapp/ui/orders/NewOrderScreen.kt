package com.salesapp.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import com.salesapp.core.channelLabel
import com.salesapp.data.Customer
import com.salesapp.resources.Res
import com.salesapp.resources.order_new_empty
import com.salesapp.resources.order_new_help
import com.salesapp.resources.order_new_needs_lpo
import com.salesapp.resources.order_new_no_access
import com.salesapp.resources.order_new_search
import com.salesapp.resources.order_new_title
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// Step 1 of a new order: pick the shop. The booking itself is on the next screen.
@Composable
fun NewOrderPage(
    onBack: () -> Unit,
    onPickCustomer: (Int) -> Unit,
    viewModel: NewOrderViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NewOrderEffect.PickCustomer -> onPickCustomer(effect.id)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(NewOrderEvent.Refresh)
        onPauseOrDispose {}
    }
    NewOrderScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun NewOrderScreen(state: NewOrderState, onEvent: (NewOrderEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopBar(stringResource(Res.string.order_new_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.order_new_no_access), modifier = Modifier.padding(16.dp))
            return@Column
        }
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(Res.string.order_new_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            SearchField(
                value = state.query,
                onValueChange = { onEvent(NewOrderEvent.QueryChanged(it)) },
                placeholder = stringResource(Res.string.order_new_search),
            )
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.customers.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(NewOrderEvent.Retry) }) }
                state.customers.isEmpty() -> item { EmptyState(stringResource(Res.string.order_new_empty)) }
                else -> items(state.customers, key = { it.id }) { customer ->
                    ShopRow(customer, onClick = { onEvent(NewOrderEvent.PickCustomer(customer.id)) })
                }
            }
        }
    }
}

@Composable
private fun ShopRow(customer: Customer, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val needsLpo = stringResource(Res.string.order_new_needs_lpo)
    val parts = listOf(
        customer.area,
        if (customer.channel.isBlank()) "" else channelLabel(customer.channel),
        if (customer.needsLpo) needsLpo else "",
    ).filter { it.isNotBlank() }
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                customer.name,
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (parts.isNotEmpty()) {
                Text(
                    parts.joinToString(" · "),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Preview
@Composable
private fun NewOrderPreview() {
    SalesTheme {
        NewOrderScreen(
            state = NewOrderState(
                loading = false,
                customers = listOf(
                    Customer(id = 1, name = "Area 47 Superstore", channel = "supermarket", area = "Area 47, Lilongwe", needsLpo = true),
                    Customer(id = 2, name = "Kawale Market Traders", channel = "minibus", area = "Kawale"),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
