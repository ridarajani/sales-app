package com.salesapp.ui.invoices

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.data.InvoiceCompany
import com.salesapp.data.InvoiceCustomer
import com.salesapp.data.InvoiceDetail
import com.salesapp.data.InvoiceLine
import com.salesapp.resources.Res
import com.salesapp.resources.invoice_date
import com.salesapp.resources.invoice_delivered
import com.salesapp.resources.invoice_delivery_note
import com.salesapp.resources.invoice_due
import com.salesapp.resources.invoice_getting
import com.salesapp.resources.invoice_line_price
import com.salesapp.resources.invoice_lines
import com.salesapp.resources.invoice_list_no_access
import com.salesapp.resources.invoice_list_plain
import com.salesapp.resources.invoice_list_vat
import com.salesapp.resources.invoice_lpo
import com.salesapp.resources.invoice_print
import com.salesapp.resources.invoice_salesman
import com.salesapp.resources.invoice_share
import com.salesapp.resources.invoice_subtotal
import com.salesapp.resources.invoice_total
import com.salesapp.resources.invoice_vat_at
import com.salesapp.resources.invoice_vat_none
import com.salesapp.resources.invoice_vehicle
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InvoiceDetailPage(
    id: Int,
    onBack: () -> Unit,
    viewModel: InvoiceDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    InvoiceDetailScreen(state, viewModel::onEvent, onBack)
}

// One invoice: who it is for, what is on it, and the totals. Print or save opens it in the phone's
// own PDF viewer; Share on WhatsApp opens the share sheet.
@Composable
fun InvoiceDetailScreen(state: InvoiceDetailState, onEvent: (InvoiceDetailEvent) -> Unit, onBack: () -> Unit) {
    val invoice = state.invoice
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(invoice?.number ?: "", onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.invoice_list_no_access), error = true, modifier = Modifier.padding(16.dp))
            invoice != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(invoice)
                    Lines(invoice)
                    Totals(invoice)
                    state.actionError?.let { Say(it, error = true) }
                    if (state.busy) {
                        Text(
                            stringResource(Res.string.invoice_getting),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                // Two things that belong together, side by side.
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SecondaryButton(
                        stringResource(Res.string.invoice_print),
                        onClick = { onEvent(InvoiceDetailEvent.Print) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        stringResource(Res.string.invoice_share),
                        onClick = { onEvent(InvoiceDetailEvent.Share) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(InvoiceDetailEvent.Retry) })
            else -> LoadingState()
        }
    }
}

@Composable
private fun Header(invoice: InvoiceDetail) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    invoice.customer.name,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Badge(
                    stringResource(if (invoice.isVat) Res.string.invoice_list_vat else Res.string.invoice_list_plain),
                    tone = BadgeTone.Role,
                )
                if (invoice.deliveredAt != null) Badge(stringResource(Res.string.invoice_delivered), tone = BadgeTone.On)
            }
            CompanyDot(invoice.companyCode)
            invoice.lpoNo.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.invoice_lpo, it), color = scheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }
            Text(
                stringResource(Res.string.invoice_date, formatIsoDate(invoice.invoiceDate)),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                stringResource(Res.string.invoice_due, formatIsoDate(invoice.dueDate)),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            invoice.vehiclePlate?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.invoice_vehicle, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            invoice.deliveryNoteNo?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.invoice_delivery_note, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            invoice.salesmanName?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.invoice_salesman, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
        }
    }
}

@Composable
private fun Lines(invoice: InvoiceDetail) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.invoice_lines))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                invoice.lines.forEach { line ->
                    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            listOf(line.brand, line.name, line.packSize).filter { it.isNotBlank() }.joinToString(" "),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(Res.string.invoice_line_price, line.quantity, formatMwk(line.pricePerCarton.toLong())),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                formatMwk(line.amount.toLong()),
                                color = scheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                                textAlign = TextAlign.End,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Totals(invoice: InvoiceDetail) {
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TotalRow(stringResource(Res.string.invoice_subtotal), formatMwk(invoice.subtotal.toLong()))
            TotalRow(
                if (invoice.isVat && invoice.vatRate > 0) {
                    stringResource(Res.string.invoice_vat_at, trimRate(invoice.vatRate))
                } else {
                    stringResource(Res.string.invoice_vat_none)
                },
                formatMwk(invoice.vat.toLong()),
            )
            TotalRow(stringResource(Res.string.invoice_total), formatMwk(invoice.total.toLong()), strong = true)
        }
    }
}

@Composable
private fun TotalRow(label: String, amount: String, strong: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = if (strong) scheme.onSurface else scheme.onSurfaceVariant,
            style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            amount,
            color = scheme.onSurface,
            style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium,
            fontWeight = if (strong) FontWeight.Bold else FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

// 17.5 -> "17.5", 16.0 -> "16".
private fun trimRate(rate: Double): String {
    val whole = rate.toLong()
    return if (rate == whole.toDouble()) whole.toString() else rate.toString()
}

private val sampleInvoice = InvoiceDetail(
    id = 3,
    number = "LV-INV-000014",
    companyCode = "LV",
    invoiceType = "vat",
    planId = 4,
    lpoNo = "LPO-5521",
    invoiceDate = "2026-10-23",
    dueDate = "2026-11-22",
    subtotal = 480000.0,
    vatRate = 17.5,
    vat = 84000.0,
    total = 564000.0,
    createdBy = "Mr. Abbas",
    salesmanName = "Chimwemwe Banda",
    planNumber = "LP-000004",
    deliveryNoteNo = "DN-000004",
    vehiclePlate = "LL 4521",
    customer = InvoiceCustomer(id = 1, name = "Area 47 Superstore", area = "Area 47", regNo = "REG-9912", tpin = "10203040"),
    company = InvoiceCompany(code = "LV", name = "Lucky Ventures", phone = "0991000001", tpin = "20304050"),
    lines = listOf(
        InvoiceLine(
            productId = 1, code = "LV-MILK", brand = "Lucky", name = "Milk", packSize = "500 ml", piecesPerCarton = 12,
            pieces = 48, quantity = "4 ctn", pricePerCarton = 90000.0, amount = 360000.0, orderNumber = "SO-000012",
        ),
        InvoiceLine(
            productId = 2, code = "LV-OIL", brand = "Lucky", name = "Cooking oil", packSize = "2 L", piecesPerCarton = 6,
            pieces = 12, quantity = "2 ctn", pricePerCarton = 60000.0, amount = 120000.0, orderNumber = "SO-000012",
        ),
    ),
)

@Preview
@Composable
private fun InvoiceDetailPreview() {
    SalesTheme {
        InvoiceDetailScreen(state = InvoiceDetailState(loading = false, invoice = sampleInvoice), onEvent = {}, onBack = {})
    }
}

@Preview
@Composable
private fun InvoicePlainPreview() {
    SalesTheme {
        InvoiceDetailScreen(
            state = InvoiceDetailState(
                loading = false,
                invoice = sampleInvoice.copy(
                    number = "ZT-INV-000009", companyCode = "ZT", invoiceType = "plain", lpoNo = "",
                    vatRate = 0.0, vat = 0.0, total = 480000.0, deliveredAt = "2026-10-23T14:00:00.000Z",
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
