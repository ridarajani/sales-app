package com.salesapp.ui.orders

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatMwk
import com.salesapp.data.BookingCompany
import com.salesapp.data.BookingCustomer
import com.salesapp.data.BookingInfo
import com.salesapp.data.LastPrice
import com.salesapp.data.Product
import com.salesapp.data.UserRow
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.order_book_add_product
import com.salesapp.resources.order_book_amount
import com.salesapp.resources.order_book_before_vat
import com.salesapp.resources.order_book_cartons
import com.salesapp.resources.order_book_date
import com.salesapp.resources.order_book_invoice
import com.salesapp.resources.order_book_lpo
import com.salesapp.resources.order_book_lpo_needed
import com.salesapp.resources.order_book_lpo_optional
import com.salesapp.resources.order_book_no_companies
import com.salesapp.resources.order_book_no_products
import com.salesapp.resources.order_book_no_salesman
import com.salesapp.resources.order_book_no_vat
import com.salesapp.resources.order_book_note
import com.salesapp.resources.order_book_note_hint
import com.salesapp.resources.order_book_overall
import com.salesapp.resources.order_book_owes
import com.salesapp.resources.order_book_owes_nothing
import com.salesapp.resources.order_book_per_carton
import com.salesapp.resources.order_book_pieces
import com.salesapp.resources.order_book_price
import com.salesapp.resources.order_book_products
import com.salesapp.resources.order_book_remove
import com.salesapp.resources.order_book_salesman
import com.salesapp.resources.order_book_search_product
import com.salesapp.resources.order_book_submit
import com.salesapp.resources.order_book_total
import com.salesapp.resources.order_book_totals
import com.salesapp.resources.order_book_two_invoices
import com.salesapp.resources.order_book_vat
import com.salesapp.resources.order_book_warehouse
import com.salesapp.resources.order_invoice_plain
import com.salesapp.resources.order_invoice_vat
import com.salesapp.resources.order_new_no_access
import com.salesapp.resources.order_new_title
import com.salesapp.ui.catalog.facilityLabel
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.DateField
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.GroupHeader
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MultilineField
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.stock.formProductTitle
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// Step 2 of a new order: everything for one shop on one scrolling form, plus a product picker.
@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun BookOrderPage(
    customerId: Int,
    onBack: () -> Unit,
    onBooked: (Int) -> Unit,
    viewModel: BookOrderViewModel = koinViewModel { parametersOf(customerId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is BookOrderEffect.Booked -> onBooked(effect.id)
            }
        }
    }
    // System back while picking a product closes the picker, not the whole order being booked.
    BackHandler(enabled = state.picking) { viewModel.onEvent(BookOrderEvent.ClosePicker) }
    BookOrderScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun BookOrderScreen(state: BookOrderState, onEvent: (BookOrderEvent) -> Unit, onBack: () -> Unit) {
    // Kept above the picker so the form comes back where it was after adding a product.
    val formScroll = rememberScrollState()
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        val booking = state.booking
        when {
            !state.allowed -> {
                TopBar(stringResource(Res.string.order_new_title), onBack = onBack)
                Say(stringResource(Res.string.order_new_no_access), modifier = Modifier.padding(16.dp))
            }
            state.loading -> {
                TopBar(stringResource(Res.string.order_new_title), onBack = onBack)
                LoadingState()
            }
            state.loadError != null || booking == null -> {
                TopBar(stringResource(Res.string.order_new_title), onBack = onBack)
                ErrorState(state.loadError.orEmpty(), onRetry = { onEvent(BookOrderEvent.Retry) }, modifier = Modifier.padding(16.dp))
            }
            state.picking -> ProductPicker(state, onEvent)
            else -> BookingForm(state, booking, formScroll, onEvent, onBack)
        }
    }
}

@Composable
private fun ColumnScope.BookingForm(
    state: BookOrderState,
    booking: BookingInfo,
    scroll: ScrollState,
    onEvent: (BookOrderEvent) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    TopBar(stringResource(Res.string.order_new_title), onBack = onBack)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(scroll).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CustomerCard(booking)
        if (booking.companies.isEmpty()) {
            Say(stringResource(Res.string.order_book_no_companies), error = true)
            return@Column
        }
        if (state.facilities.size > 1) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(Res.string.order_book_warehouse))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.facilities.forEach { code ->
                        ChoiceButton(
                            facilityLabel(code),
                            selected = state.facility == code,
                            onClick = { onEvent(BookOrderEvent.FacilityChosen(code)) },
                            modifier = Modifier.weight(1f),
                            enabled = !state.busy,
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(Res.string.order_book_invoice))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ChoiceButton(
                    stringResource(Res.string.order_invoice_vat),
                    selected = state.invoiceType == "vat",
                    onClick = { onEvent(BookOrderEvent.InvoiceType("vat")) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy,
                )
                ChoiceButton(
                    stringResource(Res.string.order_invoice_plain),
                    selected = state.invoiceType == "plain",
                    onClick = { onEvent(BookOrderEvent.InvoiceType("plain")) },
                    modifier = Modifier.weight(1f),
                    enabled = !state.busy,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(Res.string.order_book_products))
            state.lines.forEachIndexed { index, line -> LineCard(index, line, state.busy, onEvent) }
            SecondaryButton(
                stringResource(Res.string.order_book_add_product),
                onClick = { onEvent(BookOrderEvent.OpenPicker) },
                enabled = state.facility != null || state.facilities.size <= 1,
            )
        }
        state.orderCompanies.forEach { code ->
            Field(
                stringResource(Res.string.order_book_lpo, companyName(code)),
                state.lpos[code].orEmpty(),
                { onEvent(BookOrderEvent.Lpo(code, it)) },
                enabled = !state.busy,
                note = stringResource(
                    if (booking.customer.needsLpo) Res.string.order_book_lpo_needed else Res.string.order_book_lpo_optional,
                ),
            )
        }
        DateField(stringResource(Res.string.order_book_date), state.wantedOn, { onEvent(BookOrderEvent.WantedOn(it)) })
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            MultilineField(
                stringResource(Res.string.order_book_note),
                state.note,
                { onEvent(BookOrderEvent.Note(it)) },
                enabled = !state.busy,
            )
            Text(stringResource(Res.string.order_book_note_hint), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (state.lines.isNotEmpty()) TotalsCard(state)
        if (state.picksSalesman && state.orderCompanies.isNotEmpty()) SalesmanSection(state, onEvent)
    }
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        state.error?.let { Say(it, error = true) }
        if (state.busy) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = scheme.primary)
            }
        } else {
            PrimaryButton(
                stringResource(Res.string.order_book_submit),
                onClick = { onEvent(BookOrderEvent.Book) },
                enabled = booking.companies.isNotEmpty() && !state.salesmenLoading,
            )
        }
    }
}

// Who the order is for and what the shop already owes each company.
@Composable
private fun CustomerCard(booking: BookingInfo) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(booking.customer.name, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
            if (booking.customer.area.isNotBlank()) {
                Text(booking.customer.area, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
            }
            booking.companies.sortedBy { companyOrder(it.code) }.forEach { company ->
                val name = companyName(company.code)
                Text(
                    if (company.balance > 0) {
                        stringResource(Res.string.order_book_owes, name, formatMwk(company.balance))
                    } else {
                        stringResource(Res.string.order_book_owes_nothing, name)
                    },
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun LineCard(index: Int, line: BookLine, busy: Boolean, onEvent: (BookOrderEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    formProductTitle(line.product.brand, line.product.name, line.product.packSize),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                )
                CompanyDot(line.product.companyCode)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    stringResource(Res.string.order_book_cartons),
                    line.cartons,
                    { onEvent(BookOrderEvent.Cartons(index, it)) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
                NumberField(
                    stringResource(Res.string.order_book_pieces),
                    line.pieces,
                    { onEvent(BookOrderEvent.Pieces(index, it)) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
            }
            MwkField(
                stringResource(Res.string.order_book_price),
                line.price,
                { onEvent(BookOrderEvent.Price(index, it)) },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (line.amount > 0) stringResource(Res.string.order_book_amount, formatMwk(line.amount)) else "",
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    stringResource(Res.string.order_book_remove),
                    onClick = { onEvent(BookOrderEvent.RemoveLine(index)) },
                    modifier = Modifier.weight(1f),
                    enabled = !busy,
                )
            }
        }
    }
}

// One block per company (one invoice each), and the whole order when there are two.
@Composable
private fun TotalsCard(state: BookOrderState) {
    val scheme = MaterialTheme.colorScheme
    val totals = state.totals
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.order_book_totals))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                totals.forEachIndexed { i, t ->
                    if (i > 0) HorizontalDivider(color = scheme.outlineVariant)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        CompanyDot(t.code)
                        AmountRow(stringResource(Res.string.order_book_before_vat), formatMwk(t.subtotal))
                        AmountRow(
                            if (state.invoiceType == "vat") {
                                stringResource(Res.string.order_book_vat, formatRate(t.vatRate))
                            } else {
                                stringResource(Res.string.order_book_no_vat)
                            },
                            formatMwk(t.vat),
                        )
                        AmountRow(stringResource(Res.string.order_book_total), formatMwk(t.total), MaterialTheme.typography.titleSmall)
                    }
                }
                if (totals.size > 1) {
                    HorizontalDivider(color = scheme.outlineVariant)
                    Text(
                        stringResource(Res.string.order_book_two_invoices, totals.size.toString()),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.caption,
                    )
                    AmountRow(stringResource(Res.string.order_book_overall), formatMwk(totals.sumOf { it.total }), MaterialTheme.typography.titleSmall)
                }
            }
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: String, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = scheme.onSurface, style = style, modifier = Modifier.weight(1f))
        Text(amount, color = scheme.onSurface, style = style, modifier = Modifier.padding(start = 8.dp))
    }
}

// 17.5 -> "17.5", 16.0 -> "16".
private fun formatRate(rate: Double): String {
    val whole = rate.toLong()
    return if (rate == whole.toDouble()) whole.toString() else rate.toString()
}

@Composable
private fun SalesmanSection(state: BookOrderState, onEvent: (BookOrderEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.order_book_salesman))
        when {
            state.salesmenLoading -> LoadingState()
            state.salesmenError != null -> ErrorState(state.salesmenError, onRetry = { onEvent(BookOrderEvent.RetrySalesmen) })
            state.salesmen.isEmpty() -> Say(stringResource(Res.string.order_book_no_salesman))
            else -> ChoiceColumn {
                state.salesmen.forEach { salesman ->
                    ChoiceButton(
                        salesman.name,
                        selected = state.salesmanId == salesman.id,
                        onClick = { onEvent(BookOrderEvent.SalesmanChosen(salesman.id)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

// Pick a product from the chosen warehouse's companies. Never shows stock.
@Composable
private fun ColumnScope.ProductPicker(state: BookOrderState, onEvent: (BookOrderEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    TopBar(stringResource(Res.string.order_book_add_product), onBack = { onEvent(BookOrderEvent.ClosePicker) })
    val onOrder = state.lines.map { it.product.id }.toSet()
    val q = state.query.trim().lowercase()
    val groups = state.facilityCompanies.map { company ->
        company.code to state.products[company.code].orEmpty().filter { product ->
            product.id !in onOrder && (
                q.isEmpty() ||
                    formProductTitle(product.brand, product.name, product.packSize).lowercase().contains(q) ||
                    product.code.lowercase().contains(q)
                )
        }
    }.filter { it.second.isNotEmpty() }
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        SearchField(
            state.query,
            { onEvent(BookOrderEvent.Query(it)) },
            placeholder = stringResource(Res.string.order_book_search_product),
        )
        if (groups.isEmpty()) {
            Text(stringResource(Res.string.order_book_no_products), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        }
        groups.forEach { (code, products) ->
            GroupHeader(companyName(code))
            products.forEach { product ->
                ListCard(onClick = { onEvent(BookOrderEvent.AddProduct(product)) }) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            formProductTitle(product.brand, product.name, product.packSize),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(Res.string.order_book_per_carton, product.piecesPerCarton.toString()),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

private val previewProducts = listOf(
    Product(1, "KG025", "LV", "Kings", "Cooking oil", "2 L", 12),
    Product(2, "SU010", "LV", "Kings", "Sugar", "1 kg", 20),
    Product(3, "TS500", "ZT", "Zoona", "Table salt", "500 g", 24),
)

private val previewBooking = BookingInfo(
    customer = BookingCustomer(1, "Area 47 Superstore", channel = "supermarket", area = "Area 47, Lilongwe", needsLpo = true),
    companies = listOf(
        BookingCompany("LV", "Lucky Ventures", "F1", 30, 1_250_000),
        BookingCompany("ZT", "Zoona Trading", "F1", 30, 0),
        BookingCompany("ZF", "Zangafee", "F2", 14, 80_000),
    ),
    lastPrices = listOf(LastPrice(1, 54_000)),
)

private val previewState = BookOrderState(
    loading = false,
    booking = previewBooking,
    facility = "F1",
    products = mapOf("LV" to previewProducts.take(2), "ZT" to previewProducts.drop(2)),
    vatRates = mapOf("LV" to 17.5, "ZT" to 17.5),
    lines = listOf(
        BookLine(previewProducts[0], cartons = 10, pieces = 3, price = 54_000),
        BookLine(previewProducts[2], cartons = 5, price = 18_500),
    ),
    lpos = mapOf("LV" to "LPO-4471"),
    wantedOn = "2026-10-24",
    picksSalesman = true,
    salesmenFor = setOf("LV", "ZT"),
    salesmen = listOf(
        UserRow(7, "Chikondi Banda", "0991000000", "salesman", listOf("LV", "ZT")),
        UserRow(8, "Mphatso Phiri", "0881000000", "salesman", listOf("LV", "ZT")),
    ),
    salesmanId = 7,
)

@Preview
@Composable
private fun BookOrderPreview() {
    SalesTheme {
        BookOrderScreen(state = previewState, onEvent = {}, onBack = {})
    }
}

@Preview
@Composable
private fun BookOrderPickerPreview() {
    SalesTheme {
        BookOrderScreen(state = previewState.copy(lines = listOf(previewState.lines.first()), picking = true), onEvent = {}, onBack = {})
    }
}
