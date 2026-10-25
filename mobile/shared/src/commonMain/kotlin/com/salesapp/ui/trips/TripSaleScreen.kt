package com.salesapp.ui.trips

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.data.PackCompany
import com.salesapp.data.PackCustomer
import com.salesapp.data.PackPrice
import com.salesapp.data.PieceQty
import com.salesapp.data.TripOrder
import com.salesapp.data.TripOrderLine
import com.salesapp.data.TripSaleDetail
import com.salesapp.data.TripSaleInvoice
import com.salesapp.resources.Res
import com.salesapp.resources.delivery_signature_clear
import com.salesapp.resources.sale_airtel
import com.salesapp.resources.sale_another
import com.salesapp.resources.sale_cartons
import com.salesapp.resources.sale_cash
import com.salesapp.resources.sale_cash_only
import com.salesapp.resources.sale_credit
import com.salesapp.resources.sale_customer
import com.salesapp.resources.sale_customer_owes
import com.salesapp.resources.sale_customer_search
import com.salesapp.resources.sale_done
import com.salesapp.resources.sale_invoice_type
import com.salesapp.resources.sale_invoices
import com.salesapp.resources.sale_no_access
import com.salesapp.resources.sale_on_vehicle
import com.salesapp.resources.sale_order_card
import com.salesapp.resources.sale_order_gone
import com.salesapp.resources.sale_order_help
import com.salesapp.resources.sale_order_invoice
import com.salesapp.resources.sale_order_nothing
import com.salesapp.resources.sale_order_on_vehicle
import com.salesapp.resources.sale_order_price
import com.salesapp.resources.sale_order_title
import com.salesapp.resources.sale_order_too_much
import com.salesapp.resources.sale_paid_by
import com.salesapp.resources.sale_pieces
import com.salesapp.resources.sale_plain
import com.salesapp.resources.sale_price
import com.salesapp.resources.sale_products
import com.salesapp.resources.sale_proof
import com.salesapp.resources.sale_received_by
import com.salesapp.resources.sale_received_by_hint
import com.salesapp.resources.sale_reference
import com.salesapp.resources.sale_save
import com.salesapp.resources.sale_saved
import com.salesapp.resources.sale_saving
import com.salesapp.resources.sale_signature
import com.salesapp.resources.sale_signature_hint
import com.salesapp.resources.sale_title
import com.salesapp.resources.sale_too_much
import com.salesapp.resources.sale_total
import com.salesapp.resources.sale_vat
import com.salesapp.resources.sale_where
import com.salesapp.resources.trip_order_wanted
import com.salesapp.resources.trip_saved_offline
import com.salesapp.ui.cash.PhotoField
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.SignaturePad
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.loading.splitText
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TripSalePage(
    tripId: Int,
    onBack: () -> Unit,
    // Set when the leader is delivering one of the trip's orders.
    orderId: Int? = null,
    viewModel: TripSaleViewModel = koinViewModel { parametersOf(tripId, orderId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    TripSaleScreen(state, viewModel::onEvent, onBack)
}

// A sale at a stop. Everything comes from the pack on the phone, so it works with no network; the
// sale then goes to the server or into the outbox.
@Composable
fun TripSaleScreen(state: TripSaleState, onEvent: (TripSaleEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(if (state.isOrder) Res.string.sale_order_title else Res.string.sale_title), onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.sale_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.saved != null || state.savedOffline -> Saved(state, onEvent, onBack)
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(TripSaleEvent.Retry) })
            // The office took the order off the trip while the phone was away.
            state.orderGone -> Say(stringResource(Res.string.sale_order_gone), error = true, modifier = Modifier.padding(16.dp))
            state.customerId == null -> PickCustomer(state, onEvent)
            else -> Form(state, onEvent)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PickCustomer(state: TripSaleState, onEvent: (TripSaleEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SectionLabel(stringResource(Res.string.sale_customer), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    SearchField(
        value = state.query,
        onValueChange = { onEvent(TripSaleEvent.QueryChanged(it)) },
        placeholder = stringResource(Res.string.sale_customer_search),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(state.shown, key = { it.id }) { customer ->
            ListCard(onClick = { onEvent(TripSaleEvent.CustomerChosen(customer.id)) }) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(customer.name, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
                    if (customer.area.isNotBlank()) {
                        Text(customer.area, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    Text(
                        stringResource(Res.string.sale_customer_owes, formatMwk(customer.owes.toLong())),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.caption,
                    )
                    if (customer.cashOnly) {
                        Text(stringResource(Res.string.sale_cash_only), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Form(state: TripSaleState, onEvent: (TripSaleEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val customer = state.customer
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(customer?.name.orEmpty(), color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                Text(
                    stringResource(Res.string.sale_customer_owes, formatMwk((customer?.owes ?: 0.0).toLong())),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                if (customer?.cashOnly == true) {
                    Text(stringResource(Res.string.sale_cash_only), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                }
            }
        }
        if (state.isOrder) {
            OrderCard(state.order)
        } else {
            SecondaryButton(
                stringResource(Res.string.sale_customer),
                onClick = { onEvent(TripSaleEvent.ChangeCustomer) },
                enabled = !state.busy,
            )
        }
        val stops = state.trip?.stops.orEmpty()
        if (stops.isNotEmpty()) {
            SectionLabel(stringResource(Res.string.sale_where))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                stops.forEach { stop ->
                    ChoiceButton(
                        text = stop,
                        selected = state.stop == stop,
                        onClick = { onEvent(TripSaleEvent.StopChosen(stop)) },
                        enabled = !state.busy,
                    )
                }
            }
        }
        SectionLabel(stringResource(Res.string.sale_invoice_type))
        if (state.isOrder) {
            // The invoice is the one the order was booked with, so there is nothing to pick.
            Text(
                stringResource(
                    Res.string.sale_order_invoice,
                    stringResource(if (state.invoiceType == "plain") Res.string.sale_plain else Res.string.sale_vat),
                ),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("vat" to stringResource(Res.string.sale_vat), "plain" to stringResource(Res.string.sale_plain)).forEach { (code, label) ->
                    ChoiceButton(
                        text = label,
                        selected = state.invoiceType == code,
                        onClick = { onEvent(TripSaleEvent.InvoiceTypeChosen(code)) },
                        enabled = !state.busy,
                    )
                }
            }
        }
        SectionLabel(stringResource(Res.string.sale_paid_by))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // A cash-only shop is never offered "On account" at all.
            val ways = buildList {
                add("cash" to stringResource(Res.string.sale_cash))
                add("airtel" to stringResource(Res.string.sale_airtel))
                if (state.canUseCredit) add("credit" to stringResource(Res.string.sale_credit))
            }
            ways.forEach { (code, label) ->
                ChoiceButton(
                    text = label,
                    selected = state.payment == code,
                    onClick = { onEvent(TripSaleEvent.PaymentChosen(code)) },
                    enabled = !state.busy,
                )
            }
        }
        if (state.needsReference) {
            Field(
                label = stringResource(Res.string.sale_reference),
                value = state.reference,
                onValueChange = { onEvent(TripSaleEvent.ReferenceChanged(it)) },
                enabled = !state.busy,
            )
            PhotoField(
                label = stringResource(Res.string.sale_proof),
                photo = state.proof,
                cameraAvailable = state.cameraAvailable,
                busy = state.busy,
                onTake = { onEvent(TripSaleEvent.TakeProof) },
                onRemove = { onEvent(TripSaleEvent.RemoveProof) },
            )
        }
        SectionLabel(stringResource(Res.string.sale_products))
        // Nothing of the order is on the van: there is nothing to hand over here.
        if (state.isOrder && state.lines.isEmpty()) Say(stringResource(Res.string.sale_order_nothing), error = true)
        state.lines.forEach { line -> LineCard(line, state.busy, onEvent) }
        if (state.total > 0) {
            ListCard(onClick = null) {
                Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(Res.string.sale_total),
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatMwk(state.total),
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        Field(
            label = stringResource(Res.string.sale_received_by),
            value = state.receivedBy,
            onValueChange = { onEvent(TripSaleEvent.ReceivedByChanged(it)) },
            placeholder = stringResource(Res.string.sale_received_by_hint),
            enabled = !state.busy,
        )
        SectionLabel(stringResource(Res.string.sale_signature))
        SignaturePad(
            strokes = state.strokes,
            onStrokeDone = { onEvent(TripSaleEvent.StrokeDone(it)) },
            onSize = { onEvent(TripSaleEvent.PadSized(it)) },
            hint = stringResource(Res.string.sale_signature_hint),
            enabled = !state.busy,
        )
        SecondaryButton(
            stringResource(Res.string.delivery_signature_clear),
            onClick = { onEvent(TripSaleEvent.ClearSignature) },
            enabled = !state.busy && state.hasSignature,
        )
        state.actionError?.let { Say(it, error = true) }
    }
    PrimaryButton(
        stringResource(if (state.busy) Res.string.sale_saving else Res.string.sale_save),
        onClick = { onEvent(TripSaleEvent.Save) },
        enabled = !state.busy,
        modifier = Modifier.padding(16.dp),
    )
}

// One product: how much is on the van, how much they took, and the price the leader typed.
@Composable
private fun LineCard(line: SaleLine, busy: Boolean, onEvent: (TripSaleEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val productId = line.line.productId
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.line.label, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(
                    if (line.fixedPrice) Res.string.sale_order_on_vehicle else Res.string.sale_on_vehicle,
                    splitText(line.onVehicle, line.perCarton),
                ),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    label = stringResource(Res.string.sale_cartons),
                    value = line.cartons,
                    onValueChange = { onEvent(TripSaleEvent.EditCartons(productId, it)) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                if (line.perCarton > 1) {
                    NumberField(
                        label = stringResource(Res.string.sale_pieces),
                        value = line.pieces,
                        onValueChange = { onEvent(TripSaleEvent.EditPieces(productId, it)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (line.fixedPrice) {
                // The order's own price: shown so the leader can read it out, never typed.
                Text(
                    stringResource(Res.string.sale_order_price, formatMwk(line.price ?: 0)),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                MwkField(
                    label = stringResource(Res.string.sale_price),
                    amount = line.price,
                    onAmountChange = { onEvent(TripSaleEvent.EditPrice(productId, it)) },
                )
            }
            // Never more than is left on the van.
            if (line.tooMuch) {
                Say(
                    stringResource(
                        if (line.fixedPrice) Res.string.sale_order_too_much else Res.string.sale_too_much,
                        splitText(line.onVehicle, line.perCarton),
                    ),
                    error = true,
                )
            } else if (line.amount > 0) {
                Text(formatMwk(line.amount), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// Which order is being delivered, and the rule the leader works to.
@Composable
private fun OrderCard(order: TripOrder?) {
    if (order == null) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    stringResource(Res.string.sale_order_card, order.number),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                if (order.wantedOn.isNotBlank()) {
                    Text(
                        stringResource(Res.string.trip_order_wanted, formatIsoDate(order.wantedOn)),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
        Text(stringResource(Res.string.sale_order_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

// What the sale did: the invoice numbers, or a note that it is waiting on the phone.
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Saved(
    state: TripSaleState,
    onEvent: (TripSaleEvent) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (state.savedOffline) {
            // No invoice number until the server has it.
            Say(stringResource(Res.string.trip_saved_offline))
        } else {
            Say(stringResource(Res.string.sale_saved))
            state.saved?.let { saved ->
                ListCard(onClick = null) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(saved.customerName, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                        Text(
                            formatMwk(saved.total.toLong()),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
                SectionLabel(stringResource(Res.string.sale_invoices))
                saved.invoices.forEach { invoice ->
                    ListCard(onClick = null) {
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            Text(invoice.number, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(formatMwk(invoice.total.toLong()), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // After an order the leader goes back to the trip, where the order card says what is left.
        if (!state.isOrder) {
            SecondaryButton(
                stringResource(Res.string.sale_another),
                onClick = { onEvent(TripSaleEvent.Another) },
                modifier = Modifier.weight(1f),
            )
        }
        PrimaryButton(stringResource(Res.string.sale_done), onClick = onBack, modifier = Modifier.weight(1f))
    }
}

private val sampleCustomers = listOf(
    PackCustomer(
        id = 1, name = "Salima Shop", area = "Salima", channel = "upcountry", lastInvoiceType = "plain",
        companies = listOf(PackCompany("LV", 25_000.0), PackCompany("ZT", 0.0)),
        lastPrices = listOf(PackPrice(1, 25_000.0), PackPrice(2, 15_000.0)),
    ),
    PackCustomer(id = 2, name = "Cash Kiosk", area = "Nkhotakota", channel = "upcountry", cashOnly = true),
)

private fun sampleSale(cashOnly: Boolean = false, filled: Boolean = true) = TripSaleState(
    loading = false,
    trip = null,
    customers = sampleCustomers,
    customerId = if (cashOnly) 2 else 1,
    stop = "Salima market",
    invoiceType = "plain",
    payment = "cash",
    receivedBy = if (filled) "Mrs Phiri" else "",
    cameraAvailable = true,
    lines = listOf(
        SaleLine(
            line = com.salesapp.data.TripLine(productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", piecesPerCarton = 12),
            onVehicle = 72,
            cartons = if (filled) 2 else null,
            price = 25_000,
        ),
        SaleLine(
            line = com.salesapp.data.TripLine(productId = 2, brand = "Zoona", name = "Tea", packSize = "sachet", piecesPerCarton = 40),
            onVehicle = 160,
            price = 15_000,
        ),
    ),
)

private val sampleOrder = TripOrder(
    id = 31,
    number = "LV-ORD-000031",
    customerId = 1,
    customerName = "Salima Shop",
    area = "Salima",
    invoiceType = "plain",
    wantedOn = "2026-10-27",
    lines = listOf(
        TripOrderLine(
            orderLineId = 1, productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", companyCode = "LV",
            piecesPerCarton = 12, pricePerCarton = 25_000.0, pieces = 120, onVehicle = 120, toDeliver = 120,
            toDeliverQty = PieceQty(pieces = 120, cartons = 10), amount = 250_000.0,
        ),
    ),
)

private fun sampleDelivery() = sampleSale().copy(
    order = sampleOrder,
    invoiceType = "plain",
    lines = listOf(
        SaleLine(
            line = com.salesapp.data.TripLine(productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", piecesPerCarton = 12),
            onVehicle = 120,
            cartons = 10,
            price = 25_000,
            fixedPrice = true,
        ),
    ),
)

@Preview
@Composable
private fun TripSaleDeliverOrderPreview() {
    SalesTheme { TripSaleScreen(sampleDelivery(), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun TripSaleOrderGonePreview() {
    SalesTheme { TripSaleScreen(sampleDelivery().copy(orderGone = true), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun TripSalePickPreview() {
    SalesTheme {
        TripSaleScreen(TripSaleState(loading = false, customers = sampleCustomers), onEvent = {}, onBack = {})
    }
}

@Preview
@Composable
private fun TripSaleFormPreview() {
    SalesTheme { TripSaleScreen(sampleSale(), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun TripSaleCashOnlyPreview() {
    SalesTheme { TripSaleScreen(sampleSale(cashOnly = true), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun TripSaleSavedPreview() {
    SalesTheme {
        TripSaleScreen(
            sampleSale().copy(
                saved = TripSaleDetail(
                    id = 3, requestId = "a", tripId = 2, tripNumber = "TRP-000002", customerId = 1,
                    customerName = "Salima Shop", stop = "Salima market", payment = "cash", total = 50_000.0,
                    invoices = listOf(TripSaleInvoice(id = 9, number = "LV-INV-000021", companyCode = "LV", total = 50_000.0)),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun TripSaleOfflinePreview() {
    SalesTheme { TripSaleScreen(sampleSale().copy(savedOffline = true), onEvent = {}, onBack = {}) }
}
