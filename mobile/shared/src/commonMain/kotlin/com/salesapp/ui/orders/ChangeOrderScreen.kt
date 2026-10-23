package com.salesapp.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatMwk
import com.salesapp.data.OrderCompanyTotals
import com.salesapp.data.OrderDetail
import com.salesapp.data.OrderLine
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.order_change_amount
import com.salesapp.resources.order_change_cannot
import com.salesapp.resources.order_change_cartons
import com.salesapp.resources.order_change_delivery_date
import com.salesapp.resources.order_change_help
import com.salesapp.resources.order_change_invoice
import com.salesapp.resources.order_change_lpo
import com.salesapp.resources.order_change_no_vat
import com.salesapp.resources.order_change_note
import com.salesapp.resources.order_change_number
import com.salesapp.resources.order_change_order_total
import com.salesapp.resources.order_change_per_carton
import com.salesapp.resources.order_change_pieces
import com.salesapp.resources.order_change_price
import com.salesapp.resources.order_change_products
import com.salesapp.resources.order_change_save
import com.salesapp.resources.order_change_subtotal
import com.salesapp.resources.order_change_title
import com.salesapp.resources.order_change_total
import com.salesapp.resources.order_change_totals
import com.salesapp.resources.order_change_vat
import com.salesapp.ui.components.ChoiceButton
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
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ChangeOrderPage(
    id: Int,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ChangeOrderViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { onDone() }
    }
    ChangeOrderScreen(state, viewModel::onEvent, onBack)
}

// Change the quantities, prices, invoice type, LPOs, delivery date or note of an open order.
@Composable
fun ChangeOrderScreen(state: ChangeOrderState, onEvent: (ChangeOrderEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopBar(stringResource(Res.string.order_change_title), onBack = onBack)
        when {
            state.order == null && state.loadError != null ->
                ErrorState(state.loadError, onRetry = { onEvent(ChangeOrderEvent.Retry) }, modifier = Modifier.weight(1f))
            state.order == null || state.loading -> LoadingState(modifier = Modifier.weight(1f))
            !state.canChange -> Say(stringResource(Res.string.order_change_cannot), modifier = Modifier.padding(16.dp))
            else -> ChangeForm(state, state.order, onEvent)
        }
    }
}

@Composable
private fun ColumnScope.ChangeForm(state: ChangeOrderState, order: OrderDetail, onEvent: (ChangeOrderEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val enabled = !state.busy
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                stringResource(Res.string.order_change_number, order.number, order.customerName),
                style = MaterialTheme.typography.titleSmall,
            )
            Text(stringResource(Res.string.order_change_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(Res.string.order_change_invoice))
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                listOf("vat", "plain").forEach { type ->
                    ChoiceButton(
                        invoiceTypeLabel(type),
                        selected = state.invoiceType == type,
                        onClick = { onEvent(ChangeOrderEvent.InvoiceType(type)) },
                        modifier = Modifier.weight(1f),
                        enabled = enabled,
                    )
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(Res.string.order_change_products))
            state.companyCodes.forEach { code ->
                val lines = state.lines.filter { it.companyCode == code }
                if (lines.isNotEmpty()) {
                    GroupHeader(companyName(code), leading = { CompanyDot(code) })
                    lines.forEach { line -> LineCard(line, enabled, onEvent) }
                }
            }
        }

        state.companyCodes.forEach { code ->
            Field(
                stringResource(Res.string.order_change_lpo, companyName(code)),
                state.lpos[code] ?: "",
                { onEvent(ChangeOrderEvent.Lpo(code, it)) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                enabled = enabled,
            )
        }

        DateField(stringResource(Res.string.order_change_delivery_date), state.wantedOn, { onEvent(ChangeOrderEvent.WantedOn(it)) })

        MultilineField(
            stringResource(Res.string.order_change_note),
            state.note,
            { onEvent(ChangeOrderEvent.Note(it)) },
            enabled = enabled,
        )

        Totals(state)
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
            PrimaryButton(stringResource(Res.string.order_change_save), onClick = { onEvent(ChangeOrderEvent.Save) })
        }
    }
}

@Composable
private fun LineCard(line: ChangeLine, enabled: Boolean, onEvent: (ChangeOrderEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(line.title, style = MaterialTheme.typography.titleSmall)
                Text(
                    stringResource(Res.string.order_change_per_carton, line.piecesPerCarton.toString()),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.caption,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    stringResource(Res.string.order_change_cartons),
                    line.cartons,
                    { onEvent(ChangeOrderEvent.Cartons(line.lineId, it)) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                )
                NumberField(
                    stringResource(Res.string.order_change_pieces),
                    line.pieces,
                    { onEvent(ChangeOrderEvent.Pieces(line.lineId, it)) },
                    modifier = Modifier.weight(1f),
                    enabled = enabled,
                )
            }
            MwkField(
                stringResource(Res.string.order_change_price),
                line.price,
                { onEvent(ChangeOrderEvent.Price(line.lineId, it)) },
            )
            Text(
                stringResource(Res.string.order_change_amount, formatMwk(line.amount)),
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun Totals(state: ChangeOrderState) {
    val scheme = MaterialTheme.colorScheme
    val totals = state.totals
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.order_change_totals))
        totals.forEach { total ->
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CompanyDot(total.code)
                        Text(companyName(total.code), style = MaterialTheme.typography.titleSmall)
                    }
                    TotalRow(stringResource(Res.string.order_change_subtotal), formatMwk(total.subtotal))
                    TotalRow(
                        if (state.invoiceType == "vat") {
                            stringResource(Res.string.order_change_vat, rateText(total.vatRate))
                        } else {
                            stringResource(Res.string.order_change_no_vat)
                        },
                        formatMwk(total.vat),
                    )
                    HorizontalDivider(color = scheme.outline)
                    TotalRow(stringResource(Res.string.order_change_total), formatMwk(total.total), strong = true)
                }
            }
        }
        if (totals.size > 1) {
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f)) {
                    TotalRow(stringResource(Res.string.order_change_order_total), formatMwk(state.grandTotal), strong = true)
                }
            }
        }
    }
}

@Composable
private fun TotalRow(label: String, value: String, strong: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val style = if (strong) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodyMedium
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = if (strong) scheme.onSurface else scheme.onSurfaceVariant, style = style, modifier = Modifier.weight(1f))
        Text(value, color = scheme.onSurface, style = style)
    }
}

// 17.5 -> "17.5", 16.0 -> "16".
private fun rateText(rate: Double): String {
    val whole = rate.toLong()
    return if (rate == whole.toDouble()) whole.toString() else rate.toString()
}

private val previewOrder = OrderDetail(
    id = 1,
    number = "SO-000012",
    status = "open",
    facilityCode = "F1",
    invoiceType = "vat",
    wantedOn = "2026-10-24",
    note = "Deliver before noon",
    customerName = "Chipiku Stores",
    companies = listOf(
        OrderCompanyTotals("LV", "Lucky Ventures", "LPO-4411", 260_000, 17.5, 45_500, 305_500),
        OrderCompanyTotals("ZT", "Zoona Trading", null, 96_000, 17.5, 16_800, 112_800),
    ),
    lines = listOf(
        OrderLine(1, 1, "KG025", "Kings", "Cooking oil", "2 L", "LV", 12, 123, 20_000, cartons = 10, loosePieces = 3, amount = 205_000),
        OrderLine(2, 2, "SU010", "Kings", "Sugar", "1 kg", "LV", 20, 40, 27_500, cartons = 2, amount = 55_000),
        OrderLine(3, 3, "RC050", "Zoona", "Rice", "5 kg", "ZT", 4, 16, 24_000, cartons = 4, amount = 96_000),
    ),
    canChange = true,
)

@Preview
@Composable
private fun ChangeOrderPreview() {
    SalesTheme {
        ChangeOrderScreen(
            state = ChangeOrderState(
                loading = false,
                order = previewOrder,
                invoiceType = "vat",
                lines = changeLinesOf(previewOrder),
                lpos = mapOf("LV" to "LPO-4411", "ZT" to ""),
                wantedOn = "2026-10-24",
                note = "Deliver before noon",
                vatRates = mapOf("LV" to 17.5, "ZT" to 17.5),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ChangeOrderClosedPreview() {
    SalesTheme {
        ChangeOrderScreen(
            state = ChangeOrderState(loading = false, order = previewOrder.copy(status = "done", canChange = false)),
            onEvent = {},
            onBack = {},
        )
    }
}
