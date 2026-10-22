package com.salesapp.ui.customers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.Customer
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.customers_add
import com.salesapp.resources.customers_empty
import com.salesapp.resources.customers_hide_off
import com.salesapp.resources.customers_search_hint
import com.salesapp.resources.customers_show_off
import com.salesapp.resources.customers_title
import com.salesapp.resources.empty_nothing_matches
import com.salesapp.ui.components.Avatar
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.FilterChips
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel

// The page: connects the view model to the screen and turns its one-off effects into navigation.
@Composable
fun CustomersPage(
    onBack: () -> Unit,
    onOpenCustomer: (Int) -> Unit,
    onAddCustomer: () -> Unit,
    viewModel: CustomersViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CustomersEffect.OpenCustomer -> onOpenCustomer(effect.id)
                CustomersEffect.AddCustomer -> onAddCustomer()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(CustomersEvent.Refresh)
        onPauseOrDispose {}
    }
    CustomersScreen(state, viewModel::onEvent, onBack)
}

// The list of customers with search, a company filter and an "Add a customer" button.
@Composable
fun CustomersScreen(state: CustomersState, onEvent: (CustomersEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.customers_title), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SearchField(
                value = state.query,
                onValueChange = { onEvent(CustomersEvent.QueryChanged(it)) },
                placeholder = stringResource(Res.string.customers_search_hint),
            )
            if (state.companies.size > 1) {
                FilterChips(
                    options = state.companies.map { it to companyName(it) },
                    selected = state.company,
                    onSelect = { onEvent(CustomersEvent.CompanyChosen(it)) },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.customers.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(CustomersEvent.Retry) }) }
                state.customers.isEmpty() -> item {
                    val searching = state.query.isNotBlank() || state.company != null
                    EmptyState(stringResource(if (searching) Res.string.empty_nothing_matches else Res.string.customers_empty))
                }
                else -> items(state.customers, key = { it.id }) { customer ->
                    CustomerRow(customer, onClick = { onEvent(CustomersEvent.OpenCustomer(customer.id)) })
                }
            }
            item {
                TextButton(
                    onClick = { onEvent(CustomersEvent.ToggleInactive) },
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(
                        stringResource(if (state.includeInactive) Res.string.customers_hide_off else Res.string.customers_show_off),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        if (state.canAdd) {
            PrimaryButton(
                stringResource(Res.string.customers_add),
                onClick = { onEvent(CustomersEvent.AddCustomer) },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CustomerRow(customer: Customer, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Avatar(customer.name)
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            CustomerBadges(customer)
            Text(
                customer.name,
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (customer.area.isNotBlank()) {
                Text(customer.area, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                customer.companies.forEach { CompanyDot(it.code) }
            }
        }
    }
}

private val sampleCustomers = listOf(
    Customer(
        id = 1,
        name = "Area 47 Superstore",
        channel = "supermarket",
        area = "Area 47, Lilongwe",
        needsLpo = true,
        companies = listOf(
            com.salesapp.data.CustomerCompany("LV", 30),
            com.salesapp.data.CustomerCompany("ZT", 30),
        ),
    ),
    Customer(
        id = 2,
        name = "Kawale Market Traders",
        channel = "minibus",
        area = "Kawale",
        cashOnly = true,
        active = false,
        companies = listOf(com.salesapp.data.CustomerCompany("ZF", 0)),
    ),
)

@Preview
@Composable
private fun CustomersPreview() {
    SalesTheme {
        CustomersScreen(
            state = CustomersState(loading = false, customers = sampleCustomers, companies = listOf("LV", "ZT", "ZF", "GL"), canAdd = true),
            onEvent = {},
            onBack = {},
        )
    }
}
