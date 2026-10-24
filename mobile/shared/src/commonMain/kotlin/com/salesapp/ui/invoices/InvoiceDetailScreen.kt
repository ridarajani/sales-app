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
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.salesapp.core.formatIsoDate
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.core.formatMwk
import com.salesapp.data.InvoiceCompany
import com.salesapp.data.InvoiceCustomer
import com.salesapp.data.InvoiceDetail
import com.salesapp.data.InvoiceLine
import com.salesapp.resources.Res
import com.salesapp.resources.invoice_balance
import com.salesapp.resources.invoice_ledger
import com.salesapp.resources.invoice_note
import com.salesapp.resources.invoice_note_owes_less
import com.salesapp.resources.invoice_note_owes_more
import com.salesapp.resources.invoice_notes
import com.salesapp.resources.invoice_return
import com.salesapp.resources.invoice_return_after
import com.salesapp.resources.invoice_return_book
import com.salesapp.resources.invoice_return_booked
import com.salesapp.resources.invoice_return_booking
import com.salesapp.resources.invoice_return_delivery
import com.salesapp.resources.invoice_return_row
import com.salesapp.resources.invoice_return_to_book
import com.salesapp.resources.invoice_return_to_book_help
import com.salesapp.resources.invoice_returns
import com.salesapp.resources.invoice_their_number
import com.salesapp.resources.invoice_collect
import com.salesapp.resources.invoice_date
import com.salesapp.resources.invoice_delivered
import com.salesapp.resources.invoice_delivery_note
import com.salesapp.resources.invoice_due
import com.salesapp.resources.invoice_getting
import com.salesapp.resources.invoice_line_price
import com.salesapp.resources.invoice_less_discount
import com.salesapp.resources.invoice_less_wht
import com.salesapp.resources.invoice_lines
import com.salesapp.resources.invoice_list_no_access
import com.salesapp.resources.invoice_list_plain
import com.salesapp.resources.invoice_list_vat
import com.salesapp.resources.invoice_lpo
import com.salesapp.resources.invoice_paid_off
import com.salesapp.resources.invoice_payment_row
import com.salesapp.resources.invoice_payments
import com.salesapp.resources.invoice_print
import com.salesapp.resources.invoice_salesman
import com.salesapp.resources.invoice_share
import com.salesapp.resources.invoice_subtotal
import com.salesapp.resources.invoice_total
import com.salesapp.resources.invoice_trip
import com.salesapp.resources.invoice_trip_plain
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
import com.salesapp.ui.cash.modeLabel
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun InvoiceDetailPage(
    id: Int,
    onBack: () -> Unit,
    onCollect: (customerId: Int, companyCode: String, invoiceId: Int) -> Unit,
    onReturn: (invoiceId: Int) -> Unit,
    onNote: (invoiceId: Int) -> Unit,
    onLedger: (customerId: Int, companyCode: String) -> Unit,
    viewModel: InvoiceDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // A payment changes the balance, so the invoice is read again on the way back.
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(InvoiceDetailEvent.Refresh)
        onPauseOrDispose {}
    }
    InvoiceDetailScreen(state, viewModel::onEvent, onBack, onCollect, onReturn, onNote, onLedger)
}

// One invoice: who it is for, what is on it, and the totals. Print or save opens it in the phone's
// own PDF viewer; Share on WhatsApp opens the share sheet.
@Composable
fun InvoiceDetailScreen(
    state: InvoiceDetailState,
    onEvent: (InvoiceDetailEvent) -> Unit,
    onBack: () -> Unit,
    onCollect: (customerId: Int, companyCode: String, invoiceId: Int) -> Unit = { _, _, _ -> },
    onReturn: (invoiceId: Int) -> Unit = {},
    onNote: (invoiceId: Int) -> Unit = {},
    onLedger: (customerId: Int, companyCode: String) -> Unit = { _, _ -> },
) {
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
                    Payments(invoice)
                    // Phase 8: what came back and the notes on it.
                    Returns(invoice, state.busy, onBook = { onEvent(InvoiceDetailEvent.BookReturn(it)) })
                    Notes(invoice)
                    if (state.canCollect && invoice.balance > 0) {
                        SecondaryButton(
                            stringResource(Res.string.invoice_collect),
                            onClick = { onCollect(invoice.customer.id, invoice.companyCode, invoice.id) },
                            enabled = !state.busy,
                        )
                    }
                    // Two things that belong together, side by side.
                    if (invoice.canAdjust) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            SecondaryButton(
                                stringResource(Res.string.invoice_return),
                                onClick = { onReturn(invoice.id) },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            )
                            SecondaryButton(
                                stringResource(Res.string.invoice_note),
                                onClick = { onNote(invoice.id) },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                    SecondaryButton(
                        stringResource(Res.string.invoice_ledger),
                        onClick = { onLedger(invoice.customer.id, invoice.companyCode) },
                        enabled = !state.busy,
                    )
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
            // Phase 9: an invoice made on a trip names the trip and the stop it was sold at.
            invoice.tripNumber?.takeIf { it.isNotBlank() }?.let { trip ->
                val stop = invoice.tripStop?.takeIf { it.isNotBlank() }
                Text(
                    if (stop != null) stringResource(Res.string.invoice_trip, trip, stop) else stringResource(Res.string.invoice_trip_plain, trip),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodySmall,
                )
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
            // Phase 7: what came off the invoice and what is still owed on it.
            if (invoice.discount > 0) TotalRow(stringResource(Res.string.invoice_less_discount), formatMwk(invoice.discount.toLong()))
            if (invoice.wht > 0) TotalRow(stringResource(Res.string.invoice_less_wht), formatMwk(invoice.wht.toLong()))
            TotalRow(stringResource(Res.string.invoice_balance), formatMwk(invoice.balance.toLong()), strong = true)
        }
    }
}

// What came back on this invoice. A return that is not booked yet is still owed in full, so it is
// called out with the one button that fixes it.
@Composable
private fun Returns(invoice: InvoiceDetail, busy: Boolean, onBook: (Int) -> Unit) {
    if (invoice.returns.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.invoice_returns))
        invoice.returns.forEach { ret ->
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(ret.number, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(
                            formatMwk(ret.total.toLong()),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        stringResource(
                            Res.string.invoice_return_row,
                            if (ret.notTakenAtDelivery) {
                                stringResource(Res.string.invoice_return_delivery)
                            } else {
                                stringResource(Res.string.invoice_return_after)
                            },
                            ret.reason,
                        ),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Badge(
                        stringResource(if (ret.booked) Res.string.invoice_return_booked else Res.string.invoice_return_to_book),
                        tone = if (ret.booked) BadgeTone.On else BadgeTone.Off,
                    )
                    if (!ret.booked && invoice.canAdjust) {
                        Say(stringResource(Res.string.invoice_return_to_book_help), error = true)
                        PrimaryButton(
                            stringResource(if (busy) Res.string.invoice_return_booking else Res.string.invoice_return_book),
                            onClick = { onBook(ret.id) },
                            enabled = !busy,
                        )
                    }
                }
            }
        }
    }
}

// The credit and debit notes on this invoice, each saying which way it moved the balance.
@Composable
private fun Notes(invoice: InvoiceDetail) {
    if (invoice.notes.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.invoice_notes))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                invoice.notes.forEach { note ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(note.number, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            if (note.owesChange >= 0) {
                                stringResource(Res.string.invoice_note_owes_more, formatMwk(note.owesChange.toLong()))
                            } else {
                                stringResource(Res.string.invoice_note_owes_less, formatMwk(-note.owesChange.toLong()))
                            },
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(note.reason, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                        note.theirNumber.takeIf { it.isNotBlank() }?.let {
                            Text(
                                stringResource(Res.string.invoice_their_number, it),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                    }
                }
            }
        }
    }
}

// The payments that went against this invoice.
@Composable
private fun Payments(invoice: InvoiceDetail) {
    val scheme = MaterialTheme.colorScheme
    if (invoice.payments.isEmpty()) {
        if (invoice.balance <= 0) {
            Text(stringResource(Res.string.invoice_paid_off), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.invoice_payments))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                invoice.payments.forEach { payment ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(payment.number, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                            Text(
                                stringResource(
                                    Res.string.invoice_payment_row,
                                    modeLabel(payment.mode),
                                    formatIsoDate(localIsoDateFromUtcTimestamp(payment.at)),
                                ),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                        Text(
                            formatMwk(payment.amount.toLong()),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.End,
                        )
                    }
                }
                if (invoice.balance <= 0) {
                    Text(stringResource(Res.string.invoice_paid_off), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
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
