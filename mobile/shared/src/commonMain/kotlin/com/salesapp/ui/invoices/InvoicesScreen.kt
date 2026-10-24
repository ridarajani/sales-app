package com.salesapp.ui.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.salesapp.data.Company
import com.salesapp.data.InvoiceListItem
import com.salesapp.resources.Res
import com.salesapp.resources.invoice_delivered
import com.salesapp.resources.invoice_list_empty
import com.salesapp.resources.invoice_list_no_access
import com.salesapp.resources.invoice_list_no_match
import com.salesapp.resources.invoice_list_plain
import com.salesapp.resources.invoice_list_search
import com.salesapp.resources.invoice_list_title
import com.salesapp.resources.invoice_list_vat
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.FilterChips
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun InvoicesPage(
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    viewModel: InvoicesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // New invoices may have been made while this screen was away.
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(InvoicesEvent.Refresh)
        onPauseOrDispose {}
    }
    InvoicesScreen(state, viewModel::onEvent, onBack, onOpen)
}

// The invoice register, newest first: search by number, shop or LPO, and narrow by company or type.
@Composable
fun InvoicesScreen(
    state: InvoicesState,
    onEvent: (InvoicesEvent) -> Unit,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.invoice_list_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.invoice_list_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        SearchField(
            value = state.query,
            onValueChange = { onEvent(InvoicesEvent.QueryChanged(it)) },
            placeholder = stringResource(Res.string.invoice_list_search),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (state.companies.isNotEmpty()) {
            FilterChips(
                options = state.companies.map { it.code to it.name },
                selected = state.company,
                onSelect = { onEvent(InvoicesEvent.CompanyChosen(it)) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
        FilterChips(
            options = listOf(
                "vat" to stringResource(Res.string.invoice_list_vat),
                "plain" to stringResource(Res.string.invoice_list_plain),
            ),
            selected = state.type,
            onSelect = { onEvent(InvoicesEvent.TypeChosen(it)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.invoices.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(InvoicesEvent.Retry) }) }
                state.invoices.isEmpty() -> item {
                    EmptyState(stringResource(if (state.filtered) Res.string.invoice_list_no_match else Res.string.invoice_list_empty))
                }
                else -> items(state.invoices, key = { it.id }) { invoice ->
                    InvoiceRow(invoice, onClick = { onOpen(invoice.id) })
                }
            }
        }
    }
}

@Composable
private fun InvoiceRow(invoice: InvoiceListItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    invoice.number,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Badge(
                    stringResource(if (invoice.isVat) Res.string.invoice_list_vat else Res.string.invoice_list_plain),
                    tone = BadgeTone.Role,
                )
                if (invoice.delivered) Badge(stringResource(Res.string.invoice_delivered), tone = BadgeTone.On)
            }
            Text(
                invoice.customerName,
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanyDot(invoice.companyCode)
                Text(formatIsoDate(invoice.invoiceDate), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            Text(
                formatMwk(invoice.total.toLong()),
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

private val sampleInvoices = listOf(
    InvoiceListItem(
        id = 3, number = "LV-INV-000014", companyCode = "LV", invoiceType = "vat", customerId = 1,
        customerName = "Area 47 Superstore", area = "Area 47", lpoNo = "LPO-5521", invoiceDate = "2026-10-23",
        dueDate = "2026-11-22", subtotal = 480000.0, vat = 84000.0, total = 564000.0, vehiclePlate = "LL 4521",
    ),
    InvoiceListItem(
        id = 2, number = "ZT-INV-000009", companyCode = "ZT", invoiceType = "plain", customerId = 2,
        customerName = "Kanengo Wholesalers", area = "Kanengo", invoiceDate = "2026-10-23",
        dueDate = "2026-10-23", subtotal = 96000.0, total = 96000.0, delivered = true,
    ),
)

@Preview
@Composable
private fun InvoicesPreview() {
    SalesTheme {
        InvoicesScreen(
            state = InvoicesState(
                loading = false,
                invoices = sampleInvoices,
                companies = listOf(Company("LV", "Lucky Ventures", "F1"), Company("ZT", "Zoona Trading", "F1")),
            ),
            onEvent = {},
            onBack = {},
            onOpen = {},
        )
    }
}

@Preview
@Composable
private fun InvoicesEmptyPreview() {
    SalesTheme {
        InvoicesScreen(state = InvoicesState(loading = false), onEvent = {}, onBack = {}, onOpen = {})
    }
}
