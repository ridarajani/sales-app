package com.salesapp.ui.orders

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.core.splitPieces
import com.salesapp.data.OrderCompanyTotals
import com.salesapp.data.OrderDetail
import com.salesapp.data.OrderHistoryEntry
import com.salesapp.data.OrderLine
import com.salesapp.data.User
import com.salesapp.resources.Res
import com.salesapp.resources.order_detail_before_vat
import com.salesapp.resources.order_detail_booked_by
import com.salesapp.resources.order_detail_by_at
import com.salesapp.resources.order_detail_cancel
import com.salesapp.resources.order_detail_cancel_text
import com.salesapp.resources.order_detail_cancel_title
import com.salesapp.resources.order_detail_cancel_yes
import com.salesapp.resources.order_detail_change
import com.salesapp.resources.order_detail_close
import com.salesapp.resources.order_detail_close_text
import com.salesapp.resources.order_detail_close_title
import com.salesapp.resources.order_detail_close_yes
import com.salesapp.resources.order_detail_deliver_on
import com.salesapp.resources.order_detail_history
import com.salesapp.resources.order_detail_hold
import com.salesapp.resources.order_detail_line_amount
import com.salesapp.resources.order_detail_line_amount_cartons
import com.salesapp.resources.order_detail_lpo
import com.salesapp.resources.order_detail_no_access
import com.salesapp.resources.order_detail_no_date
import com.salesapp.resources.order_detail_no_lpo
import com.salesapp.resources.order_detail_not_in_stock
import com.salesapp.resources.order_detail_order_total
import com.salesapp.resources.order_detail_release
import com.salesapp.resources.order_detail_reserve
import com.salesapp.resources.order_detail_salesman
import com.salesapp.resources.order_detail_total
import com.salesapp.resources.order_detail_vat
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.ConfirmDialog
import com.salesapp.ui.components.DangerButton
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
fun OrderDetailPage(
    id: Int,
    onBack: () -> Unit,
    onChange: (Int) -> Unit,
    viewModel: OrderDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is OrderDetailEffect.OpenChange -> onChange(effect.id)
            }
        }
    }
    // Coming back from Change shows the order as it is now.
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(OrderDetailEvent.Refresh)
        onPauseOrDispose {}
    }
    OrderDetailScreen(state, viewModel::onEvent, onBack)
}

// One order: who it is for, what is on it per company with totals, what happened to it, and the buttons
// the person may use.
@Composable
fun OrderDetailScreen(state: OrderDetailState, onEvent: (OrderDetailEvent) -> Unit, onBack: () -> Unit) {
    val order = state.order
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(order?.number ?: "", onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.order_detail_no_access), error = true, modifier = Modifier.padding(16.dp))
            order != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(order, state.seesStock)
                    order.companies.forEach { company ->
                        CompanyCard(order, company, order.lines.filter { it.companyCode == company.code }, state.seesStock)
                    }
                    if (order.companies.size > 1) {
                        ListCard(onClick = null) {
                            AmountRow(stringResource(Res.string.order_detail_order_total), order.total, MaterialTheme.typography.titleSmall)
                        }
                    }
                    if (state.history.isNotEmpty()) {
                        SectionLabel(stringResource(Res.string.order_detail_history), modifier = Modifier.padding(top = 4.dp))
                        ListCard(onClick = null) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                state.history.forEach { HistoryEntry(it) }
                            }
                        }
                    }
                }
                Actions(state, onEvent)
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(OrderDetailEvent.Retry) })
            else -> LoadingState()
        }
    }
    when (state.asking) {
        OrderAction.Cancel -> ConfirmDialog(
            title = stringResource(Res.string.order_detail_cancel_title),
            text = stringResource(Res.string.order_detail_cancel_text),
            confirmLabel = stringResource(Res.string.order_detail_cancel_yes),
            onConfirm = { onEvent(OrderDetailEvent.Confirm) },
            onDismiss = { onEvent(OrderDetailEvent.Dismiss) },
            danger = true,
        )
        OrderAction.Close -> ConfirmDialog(
            title = stringResource(Res.string.order_detail_close_title),
            text = stringResource(Res.string.order_detail_close_text),
            confirmLabel = stringResource(Res.string.order_detail_close_yes),
            onConfirm = { onEvent(OrderDetailEvent.Confirm) },
            onDismiss = { onEvent(OrderDetailEvent.Dismiss) },
        )
        else -> Unit
    }
}

@Composable
private fun Header(order: OrderDetail, seesStock: Boolean) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                OrderStatusBadge(order.status)
                if (seesStock && order.shortPieces > 0 && (order.status == "open" || order.status == "hold")) ShortBadge()
            }
            Text(order.customerName, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
            val who = order.salesmanName?.takeIf { it.isNotBlank() }
                ?.let { stringResource(Res.string.order_detail_salesman, it) }
                ?: stringResource(Res.string.order_detail_booked_by, order.bookedBy)
            Text(who, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            val date = order.wantedOn?.takeIf { it.isNotBlank() }?.let { formatIsoDate(it) } ?: stringResource(Res.string.order_detail_no_date)
            Text(
                stringResource(Res.string.order_detail_deliver_on, invoiceTypeLabel(order.invoiceType), date),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (order.note.isNotBlank()) {
                Text(order.note, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            }
        }
    }
}

// One company on the order: its LPO, its lines and its totals.
@Composable
private fun CompanyCard(order: OrderDetail, company: OrderCompanyTotals, lines: List<OrderLine>, seesStock: Boolean) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                CompanyDot(company.code, modifier = Modifier.weight(1f))
                val lpo = company.lpoNo?.takeIf { it.isNotBlank() }
                Text(
                    if (lpo != null) stringResource(Res.string.order_detail_lpo, lpo) else stringResource(Res.string.order_detail_no_lpo),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            lines.forEach { LineRow(it, seesStock) }
            Column(modifier = Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                AmountRow(stringResource(Res.string.order_detail_before_vat), company.subtotal, MaterialTheme.typography.bodyMedium)
                if (order.invoiceType == "vat") {
                    AmountRow(stringResource(Res.string.order_detail_vat, formatRate(company.vatRate)), company.vat, MaterialTheme.typography.bodyMedium)
                }
                AmountRow(stringResource(Res.string.order_detail_total), company.total, MaterialTheme.typography.titleSmall)
            }
        }
    }
}

@Composable
private fun LineRow(line: OrderLine, seesStock: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            listOf(line.brand, line.name, line.packSize).filter { it.isNotBlank() }.joinToString(" "),
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            if (line.loosePieces == 0) {
                stringResource(Res.string.order_detail_line_amount_cartons, line.cartons, formatMwk(line.pricePerCarton), formatMwk(line.amount))
            } else {
                stringResource(Res.string.order_detail_line_amount, line.cartons, line.loosePieces, formatMwk(line.pricePerCarton), formatMwk(line.amount))
            },
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        if (seesStock && line.shortPieces > 0) {
            val (cartons, pieces) = splitPieces(line.shortPieces, line.piecesPerCarton)
            Text(stringResource(Res.string.order_detail_not_in_stock, cartons, pieces), color = scheme.error, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun AmountRow(label: String, amount: Long, style: TextStyle) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = scheme.onSurface, style = style, modifier = Modifier.weight(1f))
        Text(formatMwk(amount), color = scheme.onSurface, style = style, textAlign = TextAlign.End, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun HistoryEntry(entry: OrderHistoryEntry) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(entry.title, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (entry.detail.isNotBlank()) {
            Text(entry.detail, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        val date = if (entry.at.isBlank()) "" else formatIsoDate(localIsoDateFromUtcTimestamp(entry.at))
        val by = when {
            entry.by.isBlank() -> date
            date.isBlank() -> entry.by
            else -> stringResource(Res.string.order_detail_by_at, entry.by, date)
        }
        if (by.isNotBlank()) {
            Text(by, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
        }
    }
}

// The buttons at the bottom; pairs share a row, half width each, and a lone button takes the whole row.
@Composable
private fun Actions(state: OrderDetailState, onEvent: (OrderDetailEvent) -> Unit) {
    val enabled = !state.busy
    val rows = buildList<List<@Composable RowScope.(paired: Boolean) -> Unit>> {
        val first = buildList<@Composable RowScope.(Boolean) -> Unit> {
            if (state.canChange) {
                add { paired ->
                    PrimaryButton(
                        stringResource(Res.string.order_detail_change),
                        onClick = { onEvent(OrderDetailEvent.Change) },
                        enabled = enabled,
                        modifier = if (paired) Modifier.weight(1f).height(44.dp) else Modifier.weight(1f),
                    )
                }
                add {
                    DangerButton(
                        stringResource(Res.string.order_detail_cancel),
                        onClick = { onEvent(OrderDetailEvent.Act(OrderAction.Cancel)) },
                        enabled = enabled,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
        val second = buildList<@Composable RowScope.(Boolean) -> Unit> {
            if (state.canHold) {
                add {
                    SecondaryButton(stringResource(Res.string.order_detail_hold), onClick = { onEvent(OrderDetailEvent.Act(OrderAction.Hold)) }, enabled = enabled, modifier = Modifier.weight(1f))
                }
            } else if (state.canRelease) {
                add {
                    SecondaryButton(stringResource(Res.string.order_detail_release), onClick = { onEvent(OrderDetailEvent.Act(OrderAction.Release)) }, enabled = enabled, modifier = Modifier.weight(1f))
                }
            }
            if (state.canClose) {
                add {
                    SecondaryButton(stringResource(Res.string.order_detail_close), onClick = { onEvent(OrderDetailEvent.Act(OrderAction.Close)) }, enabled = enabled, modifier = Modifier.weight(1f))
                }
            }
        }
        val third = buildList<@Composable RowScope.(Boolean) -> Unit> {
            if (state.canReserve) {
                add {
                    SecondaryButton(stringResource(Res.string.order_detail_reserve), onClick = { onEvent(OrderDetailEvent.Act(OrderAction.Reserve)) }, enabled = enabled, modifier = Modifier.weight(1f))
                }
            }
        }
        listOf(first, second, third).filter { it.isNotEmpty() }.forEach { add(it) }
    }
    if (rows.isEmpty() && state.actionError == null) return
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        state.actionError?.let { Say(it, error = true) }
        rows.forEach { buttons ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val paired = buttons.size > 1
                buttons.forEach { it(paired) }
            }
        }
    }
}

// 17.5 -> "17.5", 16.0 -> "16".
private fun formatRate(rate: Double): String =
    if (rate % 1.0 == 0.0) rate.toLong().toString() else rate.toString()

private val previewAdmin = User(1, "Grace Banda", "0990000000", "admin", listOf("LV", "ZT", "ZF", "GL"))

private val previewOpenOrder = OrderDetail(
    id = 12,
    number = "SO-000012",
    status = "open",
    facilityCode = "F1",
    invoiceType = "vat",
    wantedOn = "2026-10-24",
    note = "Deliver before 10 in the morning.",
    customerName = "Area 47 Superstore",
    salesmanId = 4,
    salesmanName = "Chikondi Phiri",
    bookedById = 4,
    bookedBy = "Chikondi Phiri",
    companies = listOf(
        OrderCompanyTotals("LV", "Lucky Ventures", "LPO-5521", 510_000, 17.5, 89_250, 599_250),
        OrderCompanyTotals("ZT", "Zoona Trading", null, 96_000, 17.5, 16_800, 112_800),
    ),
    lines = listOf(
        OrderLine(1, 1, "KG025", "Kukoma", "Cooking oil", "2 L", "LV", 12, 120, 30_000, 0, 96, 10, 0, 300_000, 24),
        OrderLine(2, 2, "KG010", "Kukoma", "Cooking oil", "1 L", "LV", 24, 150, 33_600, 0, 150, 6, 6, 210_000, 0),
        OrderLine(3, 3, "ZT-S1", "Zoona", "Sugar", "1 kg", "ZT", 24, 48, 48_000, 0, 48, 2, 0, 96_000, 0),
    ),
    history = listOf(
        OrderHistoryEntry("Order booked", "3 lines for MWK 712,050.", "Chikondi Phiri", "2026-10-23T08:00:00.123456+00:00"),
        OrderHistoryEntry("Stock reserved", "2 cartons of Cooking oil 2 L are short.", "System", "2026-10-23T08:00:01Z"),
    ),
    subtotal = 606_000,
    vat = 106_050,
    total = 712_050,
    shortPieces = 24,
    canChange = true,
    canManage = true,
)

@Preview
@Composable
private fun OrderDetailOpenPreview() {
    SalesTheme {
        OrderDetailScreen(OrderDetailState(loading = false, order = previewOpenOrder, user = previewAdmin), onEvent = {}, onBack = {})
    }
}

@Preview
@Composable
private fun OrderDetailCancelledPreview() {
    SalesTheme {
        OrderDetailScreen(
            OrderDetailState(
                loading = false,
                user = previewAdmin,
                order = OrderDetail(
                    id = 9,
                    number = "SO-000009",
                    status = "cancelled",
                    facilityCode = "F2",
                    invoiceType = "plain",
                    wantedOn = null,
                    customerName = "Kanengo Wholesalers",
                    bookedById = 2,
                    bookedBy = "Grace Banda",
                    companies = listOf(OrderCompanyTotals("ZF", "Zangafee", null, 120_000, 0.0, 0, 120_000)),
                    lines = listOf(OrderLine(5, 7, "ZF1", "Zangafee", "Rice", "5 kg", "ZF", 4, 20, 24_000, 0, 0, 5, 0, 120_000, 0)),
                    history = listOf(
                        OrderHistoryEntry("Order booked", "", "Grace Banda", "2026-10-21T09:10:00Z"),
                        OrderHistoryEntry("Order cancelled", "The shop closed.", "Grace Banda", "2026-10-22T07:45:00Z"),
                    ),
                    subtotal = 120_000,
                    total = 120_000,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
