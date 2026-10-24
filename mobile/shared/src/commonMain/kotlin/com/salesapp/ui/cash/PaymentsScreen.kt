package com.salesapp.ui.cash

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.Allocation
import com.salesapp.data.PaymentDetail
import com.salesapp.data.PaymentListItem
import com.salesapp.resources.Res
import com.salesapp.resources.collect_mode_airtel
import com.salesapp.resources.collect_mode_bank
import com.salesapp.resources.collect_mode_cash
import com.salesapp.resources.collect_on_account
import com.salesapp.resources.collect_owes_now
import com.salesapp.resources.collect_went_invoice
import com.salesapp.resources.collect_went_on
import com.salesapp.resources.collect_went_opening
import com.salesapp.resources.payment_certificate
import com.salesapp.resources.payment_collected_by
import com.salesapp.resources.payment_custody_counted
import com.salesapp.resources.payment_custody_handed
import com.salesapp.resources.payment_custody_with
import com.salesapp.resources.payment_discount
import com.salesapp.resources.payment_for_invoice
import com.salesapp.resources.payment_proof
import com.salesapp.resources.payment_reference
import com.salesapp.resources.payment_signature
import com.salesapp.resources.payment_wht
import com.salesapp.resources.payments_empty
import com.salesapp.resources.payments_no_access
import com.salesapp.resources.payments_no_match
import com.salesapp.resources.payments_search
import com.salesapp.resources.payments_title
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
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PaymentsPage(
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    viewModel: PaymentsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(PaymentsEvent.Refresh)
        onPauseOrDispose {}
    }
    PaymentsScreen(state, viewModel::onEvent, onBack, onOpen)
}

// Payments, newest first, with a search and a filter for how they were paid.
@Composable
fun PaymentsScreen(
    state: PaymentsState,
    onEvent: (PaymentsEvent) -> Unit,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.payments_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.payments_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        SearchField(
            value = state.query,
            onValueChange = { onEvent(PaymentsEvent.QueryChanged(it)) },
            placeholder = stringResource(Res.string.payments_search),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        FilterChips(
            options = listOf(
                "cash" to stringResource(Res.string.collect_mode_cash),
                "bank" to stringResource(Res.string.collect_mode_bank),
                "airtel" to stringResource(Res.string.collect_mode_airtel),
            ),
            selected = state.mode,
            onSelect = { onEvent(PaymentsEvent.ModeChosen(it)) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.payments.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(PaymentsEvent.Retry) }) }
                state.payments.isEmpty() -> item {
                    EmptyState(stringResource(if (state.filtered) Res.string.payments_no_match else Res.string.payments_empty))
                }
                else -> items(state.payments, key = { it.id }) { payment ->
                    PaymentRow(payment, onClick = { onOpen(payment.id) })
                }
            }
        }
    }
}

@Composable
private fun PaymentRow(payment: PaymentListItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    payment.number,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Badge(modeLabel(payment.mode), tone = BadgeTone.Role)
            }
            Text(
                payment.customerName,
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanyDot(payment.companyCode)
                Text(
                    formatIsoDate(localIsoDateFromUtcTimestamp(payment.createdAt)),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.caption,
                )
            }
            payment.invoiceNumber?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.payment_for_invoice, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            Text(
                formatMwk(payment.amount.toLong()),
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
fun PaymentDetailPage(
    id: Int,
    onBack: () -> Unit,
    viewModel: PaymentDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PaymentDetailScreen(state, viewModel::onEvent, onBack)
}

// One payment: what was paid, where it went, and the proof and signature kept with it.
@Composable
fun PaymentDetailScreen(state: PaymentDetailState, onEvent: (PaymentDetailEvent) -> Unit, onBack: () -> Unit) {
    val payment = state.payment
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(payment?.number ?: "", onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.payments_no_access), error = true, modifier = Modifier.padding(16.dp))
            payment != null -> Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ListCard(onClick = null) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(
                                payment.customerName,
                                color = scheme.onSurface,
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.weight(1f, fill = false),
                            )
                            Badge(modeLabel(payment.mode), tone = BadgeTone.Role)
                        }
                        CompanyDot(payment.companyCode)
                        Text(
                            formatMwk(payment.amount.toLong()),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                        payment.invoiceNumber?.takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(Res.string.payment_for_invoice, it), color = scheme.onSurface, style = MaterialTheme.typography.bodySmall)
                        }
                        payment.reference.takeIf { it.isNotBlank() }?.let {
                            Text(stringResource(Res.string.payment_reference, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        if (payment.receivedBy.isNotBlank()) {
                            Text(payment.receivedBy, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                        Text(
                            stringResource(Res.string.payment_collected_by, payment.collectedBy),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.caption,
                        )
                        Text(
                            formatIsoDate(localIsoDateFromUtcTimestamp(payment.createdAt)),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.caption,
                        )
                        if (payment.discount > 0) {
                            Text(
                                stringResource(Res.string.payment_discount, formatMwk(payment.discount.toLong())),
                                color = scheme.onSurface,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        if (payment.wht > 0) {
                            Text(
                                stringResource(Res.string.payment_wht, formatMwk(payment.wht.toLong())),
                                color = scheme.onSurface,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            payment.whtCertificate.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    stringResource(Res.string.payment_certificate, it),
                                    color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.caption,
                                )
                            }
                        }
                        if (payment.note.isNotBlank()) {
                            Text(payment.note, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                // Cash stays the collector's responsibility until the cashier counts it.
                Custody(payment)
                SectionLabel(stringResource(Res.string.collect_went_on))
                ListCard(onClick = null) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        payment.allocations.forEach { allocation -> WentRow(allocation) }
                        if (payment.onAccount > 0) {
                            Text(
                                stringResource(Res.string.collect_on_account, formatMwk(payment.onAccount.toLong())),
                                color = scheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            stringResource(Res.string.collect_owes_now, formatMwk(payment.owesNow.toLong())),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                state.proof?.let {
                    SectionLabel(stringResource(Res.string.payment_proof))
                    ServerPicture(it)
                }
                state.signature?.let {
                    SectionLabel(stringResource(Res.string.payment_signature))
                    ServerPicture(it)
                }
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(PaymentDetailEvent.Retry) })
            else -> LoadingState()
        }
    }
}

@Composable
private fun Custody(payment: PaymentDetail) {
    val text = when (payment.custody) {
        "with" -> stringResource(Res.string.payment_custody_with, payment.collectedBy)
        "handed" -> stringResource(Res.string.payment_custody_handed, payment.handoverNumber.orEmpty())
        "counted" -> stringResource(Res.string.payment_custody_counted)
        else -> return
    }
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun WentRow(allocation: Allocation) {
    val amount = formatMwk(allocation.amount.toLong())
    Text(
        allocation.invoiceNumber?.let { stringResource(Res.string.collect_went_invoice, it, amount) }
            ?: stringResource(Res.string.collect_went_opening, amount),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private val samplePayments = listOf(
    PaymentListItem(
        id = 3, number = "LV-RCT-000007", companyCode = "LV", customerName = "Area 47 Superstore",
        invoiceNumber = "LV-INV-000014", mode = "cash", amount = 700_000.0, collectedBy = "Chimwemwe Banda",
        custody = "with", createdAt = "2026-10-24T09:10:00.000Z",
    ),
    PaymentListItem(
        id = 2, number = "ZT-RCT-000003", companyCode = "ZT", customerName = "Kanengo Wholesalers",
        mode = "airtel", amount = 96_000.0, reference = "PP251023.1420.A12345", collectedBy = "Mr. Abbas",
        custody = "na", createdAt = "2026-10-23T15:40:00.000Z",
    ),
)

@Preview
@Composable
private fun PaymentsPreview() {
    SalesTheme {
        PaymentsScreen(state = PaymentsState(loading = false, payments = samplePayments), onEvent = {}, onBack = {}, onOpen = {})
    }
}

@Preview
@Composable
private fun PaymentsEmptyPreview() {
    SalesTheme {
        PaymentsScreen(state = PaymentsState(loading = false), onEvent = {}, onBack = {}, onOpen = {})
    }
}

@Preview
@Composable
private fun PaymentDetailPreview() {
    SalesTheme {
        PaymentDetailScreen(
            state = PaymentDetailState(
                loading = false,
                payment = PaymentDetail(
                    id = 3,
                    number = "LV-RCT-000007",
                    companyCode = "LV",
                    customerId = 1,
                    customerName = "Area 47 Superstore",
                    invoiceId = 3,
                    invoiceNumber = "LV-INV-000014",
                    mode = "cash",
                    amount = 700_000.0,
                    discount = 10_000.0,
                    wht = 5_000.0,
                    whtCertificate = "WHT-2026-114",
                    receivedBy = "Grace Banda",
                    collectedBy = "Chimwemwe Banda",
                    custody = "with",
                    createdAt = "2026-10-24T09:10:00.000Z",
                    allocations = listOf(
                        Allocation(invoiceId = 3, invoiceNumber = "LV-INV-000014", amount = 564_000.0),
                        Allocation(invoiceId = null, amount = 136_000.0),
                    ),
                    owesNow = 464_000.0,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
