package com.salesapp.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.core.splitPieces
import com.salesapp.data.NotLoaded
import com.salesapp.data.PieceQty
import com.salesapp.data.TripDetail
import com.salesapp.data.TripExpense
import com.salesapp.data.TripLine
import com.salesapp.data.TripOrder
import com.salesapp.data.TripOrderLine
import com.salesapp.data.TripOutbox
import com.salesapp.data.TripSale
import com.salesapp.resources.Res
import com.salesapp.resources.trip_add_expense
import com.salesapp.resources.trip_cash_now
import com.salesapp.resources.trip_close_blocked
import com.salesapp.resources.trip_closed_cash
import com.salesapp.resources.trip_closed_matches
import com.salesapp.resources.trip_closed_note
import com.salesapp.resources.trip_end_day
import com.salesapp.resources.trip_end_trip
import com.salesapp.resources.trip_expenses
import com.salesapp.resources.trip_float
import com.salesapp.resources.trip_float_box
import com.salesapp.resources.trip_float_left
import com.salesapp.resources.trip_float_spent
import com.salesapp.resources.trip_gate_pass
import com.salesapp.resources.trip_line_back
import com.salesapp.resources.trip_line_missing
import com.salesapp.resources.trip_line_on_vehicle
import com.salesapp.resources.trip_line_for_orders
import com.salesapp.resources.trip_line_for_sale
import com.salesapp.resources.trip_line_sold
import com.salesapp.resources.trip_line_to_load
import com.salesapp.resources.trip_edit
import com.salesapp.resources.trip_load_action
import com.salesapp.resources.trip_load_cartons
import com.salesapp.resources.trip_load_pieces
import com.salesapp.resources.trip_load_help
import com.salesapp.resources.trip_load_not_loaded
import com.salesapp.resources.trip_load_not_loaded_row
import com.salesapp.resources.trip_load_title
import com.salesapp.resources.trip_load_vehicle
import com.salesapp.resources.trip_loading
import com.salesapp.resources.trip_no_sales
import com.salesapp.resources.trip_offline_pack
import com.salesapp.resources.trip_on_this_trip
import com.salesapp.resources.trip_order_all_done
import com.salesapp.resources.trip_order_deliver
import com.salesapp.resources.trip_order_line
import com.salesapp.resources.trip_order_to_deliver
import com.salesapp.resources.trip_order_waiting
import com.salesapp.resources.trip_order_wanted
import com.salesapp.resources.trip_orders
import com.salesapp.resources.trip_outbox_both
import com.salesapp.resources.trip_outbox_drop
import com.salesapp.resources.trip_outbox_expenses
import com.salesapp.resources.trip_outbox_failed
import com.salesapp.resources.trip_outbox_help
import com.salesapp.resources.trip_outbox_one
import com.salesapp.resources.trip_outbox_sales
import com.salesapp.resources.trip_outbox_sent
import com.salesapp.resources.trip_outbox_unreadable
import com.salesapp.resources.trip_overspent
import com.salesapp.resources.trip_record_sale
import com.salesapp.resources.trip_sale_pending
import com.salesapp.resources.trip_sale_row
import com.salesapp.resources.trip_sales
import com.salesapp.resources.trip_send_now
import com.salesapp.resources.trip_sending
import com.salesapp.resources.trip_title
import com.salesapp.resources.trips_closed
import com.salesapp.resources.trips_minibus
import com.salesapp.resources.trips_no_access
import com.salesapp.resources.trips_on_road
import com.salesapp.resources.trips_upcountry
import com.salesapp.resources.trips_waiting
import com.salesapp.ui.cash.BigMoney
import com.salesapp.ui.cash.MoneyRow
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.components.VehiclePicker
import com.salesapp.ui.loading.cartonsText
import com.salesapp.ui.loading.splitText
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TripDetailPage(
    id: Int,
    onBack: () -> Unit,
    onSell: (Int) -> Unit,
    onExpense: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onEdit: (Int) -> Unit,
    onDeliver: (Int, Int) -> Unit,
    viewModel: TripDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Coming back from a sale or an expense shows the new numbers and the outbox as it is now.
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(TripDetailEvent.Refresh)
        onPauseOrDispose {}
    }
    TripDetailScreen(state, viewModel::onEvent, onBack, onSell, onExpense, onClose, onEdit, onDeliver)
}

// One trip: what is on the van, the sales and expenses, the float, and whatever this phone is still
// holding for it.
@Composable
fun TripDetailScreen(
    state: TripDetailState,
    onEvent: (TripDetailEvent) -> Unit,
    onBack: () -> Unit,
    onSell: (Int) -> Unit = {},
    onExpense: (Int) -> Unit = {},
    onClose: (Int) -> Unit = {},
    onEdit: (Int) -> Unit = {},
    // Trip and order: delivering one of the orders the trip carries.
    onDeliver: (Int, Int) -> Unit = { _, _ -> },
) {
    val trip = state.trip
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            trip?.let {
                stringResource(
                    Res.string.trip_title,
                    stringResource(if (it.isMinibus) Res.string.trips_minibus else Res.string.trips_upcountry),
                    it.route,
                )
            } ?: "",
            onBack = onBack,
        )
        when {
            !state.allowed -> Say(stringResource(Res.string.trips_no_access), error = true, modifier = Modifier.padding(16.dp))
            trip != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(trip, state.fromPhone)
                    // Sales already paid for in cash that the phone cannot read. Once the file is
                    // with the office the line says so, and the trip waits for Admin.
                    if (state.outboxProblemOpen) {
                        Say(stringResource(Res.string.trip_outbox_sent), error = true)
                    } else if (state.hasUnreadable) {
                        Say(stringResource(Res.string.trip_outbox_unreadable), error = true)
                    }
                    Outbox(state, onEvent)
                    state.actionError?.let { Say(it, error = true) }
                    if (trip.canLoad) LoadForm(state, trip, onEvent)
                    NotLoadedCard(trip.notLoaded)
                    if (trip.showsMoney && !trip.waitingToLoad) FloatBox(trip)
                    if (trip.closed) Closed(trip)
                    Orders(state, trip, onDeliver)
                    SectionLabel(stringResource(Res.string.trip_on_this_trip))
                    trip.lines.forEach { line ->
                        LineRow(
                            line = line,
                            onVehicle = state.onVehicle[line.productId] ?: line.onVehicle,
                            forOrders = state.forOrders[line.productId] ?: line.forOrders,
                            forSale = state.forSale[line.productId] ?: line.sellable,
                            trip = trip,
                        )
                    }
                    Sales(trip, state.outbox)
                    Expenses(trip)
                }
                Actions(state, trip, onSell, onExpense, onClose, onEdit)
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(TripDetailEvent.Retry) })
            else -> LoadingState()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Header(trip: TripDetail, fromPhone: Boolean) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(trip.number, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                Badge(
                    stringResource(
                        when {
                            trip.onTheRoad -> Res.string.trips_on_road
                            trip.closed -> Res.string.trips_closed
                            else -> Res.string.trips_waiting
                        },
                    ),
                    tone = when {
                        trip.onTheRoad -> BadgeTone.On
                        trip.closed -> BadgeTone.Role
                        else -> BadgeTone.Off
                    },
                )
            }
            Text(formatIsoDate(trip.tripDate), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Text(trip.leaderName, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            if (trip.staff.isNotBlank()) {
                Text(trip.staff, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            trip.vehiclePlate?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (trip.stops.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    trip.stops.forEach { stop -> Badge(stop, tone = BadgeTone.Role) }
                }
            }
            trip.gatePassNo?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.trip_gate_pass, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            if (fromPhone) {
                Text(
                    stringResource(Res.string.trip_offline_pack),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// What this phone is still holding, and the one button that sends it.
@Composable
private fun Outbox(state: TripDetailState, onEvent: (TripDetailEvent) -> Unit) {
    val outbox = state.outbox
    if (outbox.isEmpty) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Say(
            when {
                outbox.sales.size == 1 && outbox.expenses.isEmpty() -> stringResource(Res.string.trip_outbox_one)
                outbox.expenses.isEmpty() -> stringResource(Res.string.trip_outbox_sales, outbox.sales.size)
                outbox.sales.isEmpty() -> stringResource(Res.string.trip_outbox_expenses, outbox.expenses.size)
                else -> stringResource(Res.string.trip_outbox_both, outbox.count)
            },
            error = true,
        )
        Text(stringResource(Res.string.trip_outbox_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        // Anything the server would not take, with its reason and a way to drop it.
        outbox.sales.filter { it.failed != null }.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Say(stringResource(Res.string.trip_outbox_failed, item.failed.orEmpty()), error = true)
                SecondaryButton(
                    stringResource(Res.string.trip_outbox_drop),
                    onClick = { onEvent(TripDetailEvent.DropSale(item.requestId)) },
                    enabled = !state.syncing,
                )
            }
        }
        outbox.expenses.filter { it.failed != null }.forEach { item ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Say(stringResource(Res.string.trip_outbox_failed, item.failed.orEmpty()), error = true)
                SecondaryButton(
                    stringResource(Res.string.trip_outbox_drop),
                    onClick = { onEvent(TripDetailEvent.DropExpense(item.requestId)) },
                    enabled = !state.syncing,
                )
            }
        }
        PrimaryButton(
            stringResource(if (state.syncing) Res.string.trip_sending else Res.string.trip_send_now),
            onClick = { onEvent(TripDetailEvent.SendNow) },
            enabled = !state.syncing,
        )
    }
}

// The warehouse types what actually went on the van.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LoadForm(state: TripDetailState, trip: TripDetail, onEvent: (TripDetailEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(Res.string.trip_load_title))
        Text(stringResource(Res.string.trip_load_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        state.loadLines.forEach { line ->
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(line.label, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        NumberField(
                            label = stringResource(Res.string.trip_load_cartons),
                            value = line.cartons,
                            onValueChange = { onEvent(TripDetailEvent.EditLoadCartons(line.productId, it)) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                        if (line.perCarton > 1) {
                            NumberField(
                                label = stringResource(Res.string.trip_load_pieces),
                                value = line.pieces,
                                onValueChange = { onEvent(TripDetailEvent.EditLoadPieces(line.productId, it)) },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
        if (state.vehicles.isNotEmpty()) {
            SectionLabel(stringResource(Res.string.trip_load_vehicle))
            VehiclePicker(
                vehicles = state.vehicles,
                chosenId = state.loadVehicleId ?: trip.vehicleId,
                onChoose = { onEvent(TripDetailEvent.LoadVehicleChosen(it)) },
                enabled = !state.busy,
            )
        }
        PrimaryButton(
            stringResource(if (state.busy) Res.string.trip_loading else Res.string.trip_load_action),
            onClick = { onEvent(TripDetailEvent.Load) },
            enabled = !state.busy,
        )
    }
}

@Composable
private fun NotLoadedCard(rows: List<NotLoaded>) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(stringResource(Res.string.trip_load_not_loaded))
        rows.forEach { row ->
            Say(
                stringResource(Res.string.trip_load_not_loaded_row, row.product, cartonsText(row.cartons, row.loosePieces)),
                error = true,
            )
        }
    }
}

// The float, what has been spent of it, and what the leader should be holding now.
@Composable
private fun FloatBox(trip: TripDetail) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BigMoney(stringResource(Res.string.trip_cash_now), trip.cashInHand)
        SectionLabel(stringResource(Res.string.trip_float_box))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MoneyRow(stringResource(Res.string.trip_float), trip.float ?: 0.0)
                MoneyRow(stringResource(Res.string.trip_float_spent), trip.expensesTotal ?: 0.0, negative = true)
                MoneyRow(stringResource(Res.string.trip_float_left), trip.floatLeft, strong = true)
            }
        }
        // More spent than the float means the extra is coming out of the cash from sales.
        if (trip.overspent) Say(stringResource(Res.string.trip_overspent), error = true)
    }
}

@Composable
private fun Closed(trip: TripDetail) {
    val scheme = MaterialTheme.colorScheme
    val missing = trip.lines.sumOf { it.missing ?: 0 }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (trip.showsMoney) {
            Say(
                stringResource(
                    Res.string.trip_closed_cash,
                    formatMwk((trip.cashCounted ?: 0.0).toLong()),
                    formatMwk((trip.cashExpected ?: 0.0).toLong()),
                ),
                error = (trip.cashDifference ?: 0.0) != 0.0 || missing > 0,
            )
        }
        if ((trip.cashDifference ?: 0.0) == 0.0 && missing == 0) {
            Text(stringResource(Res.string.trip_closed_matches), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (trip.closeNote.isNotBlank()) {
            Text(stringResource(Res.string.trip_closed_note, trip.closeNote), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// The orders this trip carries. While it is out, the leader hands each one over from here.
@Composable
private fun Orders(state: TripDetailState, trip: TripDetail, onDeliver: (Int, Int) -> Unit) {
    if (trip.orders.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.trip_orders))
        trip.orders.forEach { order ->
            val left = state.toDeliver(order)
            OrderCard(order, left, trip, waiting = state.waitingOn(order))
            if (trip.canSell && left.values.sum() > 0) {
                PrimaryButton(
                    stringResource(Res.string.trip_order_deliver),
                    onClick = { onDeliver(trip.id, order.id) },
                    enabled = !state.busy,
                )
            }
        }
    }
}

@Composable
private fun OrderCard(order: TripOrder, left: Map<Int, Int>, trip: TripDetail, waiting: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val amount = order.amount
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(order.customerName, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                if (trip.showsMoney && amount != null) {
                    Text(formatMwk(amount.toLong()), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(order.number, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            if (order.area.isNotBlank()) {
                Text(order.area, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            if (order.wantedOn.isNotBlank()) {
                Text(
                    stringResource(Res.string.trip_order_wanted, formatIsoDate(order.wantedOn)),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.caption,
                )
            }
            val lines = order.lines.filter { (left[it.productId] ?: 0) > 0 }
            if (lines.isEmpty()) {
                // A closed trip says nothing here: what an order did not get was set aside for it
                // again, which is the office's business now, not the leader's.
                if (!trip.closed) {
                    Text(stringResource(Res.string.trip_order_all_done), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            } else {
                Text(stringResource(Res.string.trip_order_to_deliver), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                lines.forEach { line -> OrderLineRow(line, left[line.productId] ?: 0) }
            }
            // A delivery saved with no signal: the numbers above already allow for it.
            if (waiting) {
                Text(stringResource(Res.string.trip_order_waiting), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
        }
    }
}

@Composable
private fun OrderLineRow(line: TripOrderLine, pieces: Int) {
    Text(
        stringResource(Res.string.trip_order_line, line.label, splitText(pieces, line.perCarton)),
        color = MaterialTheme.colorScheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LineRow(line: TripLine, onVehicle: Int, forOrders: Int, forSale: Int, trip: TripDetail) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(line.label, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                if (trip.waitingToLoad) {
                    stringResource(Res.string.trip_line_to_load, cartonsText(line.plannedQty.cartons, line.plannedQty.loosePieces))
                } else {
                    stringResource(
                        Res.string.trip_line_sold,
                        cartonsText(line.soldQty.cartons, line.soldQty.loosePieces),
                        cartonsText(line.loadedQty.cartons, line.loadedQty.loosePieces),
                    )
                },
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
            if (trip.onTheRoad) {
                Text(
                    stringResource(Res.string.trip_line_on_vehicle, splitText(onVehicle, line.piecesPerCarton)),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            // Goods held for the trip's own orders cannot be sold to anybody else.
            if (forOrders > 0) {
                Text(
                    stringResource(Res.string.trip_line_for_orders, splitText(forOrders, line.piecesPerCarton)),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.caption,
                )
                if (trip.onTheRoad) {
                    Text(
                        stringResource(Res.string.trip_line_for_sale, splitText(forSale, line.piecesPerCarton)),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
            if (trip.closed) {
                line.returned?.let {
                    Text(
                        stringResource(Res.string.trip_line_back, splitText(it, line.piecesPerCarton)),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.caption,
                    )
                }
                line.missing?.takeIf { it > 0 }?.let {
                    Text(
                        stringResource(Res.string.trip_line_missing, splitText(it, line.piecesPerCarton)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.caption,
                    )
                }
            }
        }
    }
}

// The sales the server has, then the ones still on this phone.
@Composable
private fun Sales(trip: TripDetail, outbox: TripOutbox) {
    if (!trip.showsMoney) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.trip_sales))
        if (trip.sales.isEmpty() && outbox.sales.isEmpty()) {
            Say(stringResource(Res.string.trip_no_sales))
        }
        trip.sales.forEach { sale -> SaleRow(sale) }
        outbox.sales.forEach { item ->
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(item.request.stop.ifBlank { trip.route }, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                    // No number until the server has it.
                    Text(stringResource(Res.string.trip_sale_pending), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                }
            }
        }
    }
}

@Composable
private fun SaleRow(sale: TripSale) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sale.customerName, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(formatMwk(sale.total.toLong()), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            }
            if (sale.stop.isNotBlank()) {
                Text(sale.stop, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            sale.invoices.forEach { invoice ->
                Text(
                    stringResource(Res.string.trip_sale_row, invoice.number, formatMwk(invoice.total.toLong())),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.caption,
                )
            }
        }
    }
}

@Composable
private fun Expenses(trip: TripDetail) {
    if (!trip.showsMoney || trip.expenses.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.trip_expenses))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                trip.expenses.forEach { expense -> ExpenseRow(expense) }
            }
        }
    }
}

@Composable
private fun ExpenseRow(expense: TripExpense) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(expense.description, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Text(expense.number, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
        }
        Text("− ${formatMwk(expense.amount.toLong())}", color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Actions(
    state: TripDetailState,
    trip: TripDetail,
    onSell: (Int) -> Unit,
    onExpense: (Int) -> Unit,
    onClose: (Int) -> Unit,
    onEdit: (Int) -> Unit,
) {
    if (!trip.canSell && !state.canClose && !state.blockedByOutbox && !trip.canEdit) return
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (trip.canEdit) {
            SecondaryButton(
                stringResource(Res.string.trip_edit),
                onClick = { onEdit(trip.id) },
                enabled = !state.busy,
            )
        }
        if (trip.canSell) {
            PrimaryButton(
                stringResource(Res.string.trip_record_sale),
                onClick = { onSell(trip.id) },
                enabled = !state.busy,
            )
            SecondaryButton(
                stringResource(Res.string.trip_add_expense),
                onClick = { onExpense(trip.id) },
                enabled = !state.busy,
            )
        }
        // Closing waits until everything on the phone has reached the server, and until Admin has
        // looked at any sales it could not read.
        if (state.outboxProblemOpen && trip.canClose) {
            Say(stringResource(Res.string.trip_outbox_sent), error = true)
        } else if (state.blockedByOutbox) {
            Say(stringResource(Res.string.trip_close_blocked), error = true)
        } else if (state.canClose) {
            SecondaryButton(
                stringResource(if (trip.isMinibus) Res.string.trip_end_day else Res.string.trip_end_trip),
                onClick = { onClose(trip.id) },
                enabled = !state.busy,
            )
        }
    }
}

private fun qty(pieces: Int, perCarton: Int): PieceQty {
    val (cartons, loose) = splitPieces(pieces, perCarton)
    return PieceQty(pieces = pieces, cartons = cartons, loosePieces = loose)
}

private val sampleLines = listOf(
    TripLine(
        productId = 1, code = "LV-MILK", brand = "Lucky", name = "Milk", packSize = "500 ml", companyCode = "LV",
        piecesPerCarton = 12, planned = 120, loaded = 120, sold = 48,
        plannedQty = qty(120, 12), loadedQty = qty(120, 12), soldQty = qty(48, 12),
        onVehicle = 72, onVehicleQty = qty(72, 12),
    ),
    TripLine(
        productId = 2, code = "ZT-TEA", brand = "Zoona", name = "Tea", packSize = "sachet", companyCode = "ZT",
        piecesPerCarton = 40, planned = 200, loaded = 200, sold = 40,
        plannedQty = qty(200, 40), loadedQty = qty(200, 40), soldQty = qty(40, 40),
        onVehicle = 160, onVehicleQty = qty(160, 40),
    ),
)

private val sampleTrip = TripDetail(
    id = 2,
    number = "TRP-000002",
    kind = "upcountry",
    facilityCode = "F1",
    facilityName = "Facility 1",
    companyCode = "LV",
    tripDate = "2026-10-25",
    route = "Salima and Nkhotakota",
    stops = listOf("Salima market", "Nkhotakota"),
    vehicleId = 1,
    vehiclePlate = "LL 5678",
    leaderId = 4,
    leaderName = "Chimwemwe Banda",
    staff = "Chimwemwe, T. Mwale",
    float = 100_000.0,
    status = "out",
    gatePassNo = "GP-000012",
    cashSales = 230_000.0,
    cashExpenses = 30_000.0,
    cashNow = 300_000.0,
    lines = sampleLines,
    sales = listOf(
        TripSale(
            id = 1, customerId = 1, customerName = "Salima Shop", stop = "Salima market", payment = "cash",
            total = 230_000.0, soldAt = "2026-10-25T09:00:00.000Z", receivedBy = "Mrs Phiri", soldBy = "Chimwemwe Banda",
            invoices = listOf(com.salesapp.data.TripSaleInvoice(id = 9, number = "LV-INV-000021", companyCode = "LV", total = 230_000.0)),
        ),
    ),
    expenses = listOf(TripExpense(id = 1, number = "LV-EXP-000011", companyCode = "LV", amount = 30_000.0, description = "Fuel · trip TRP-000002")),
    salesTotal = 230_000.0,
    expensesTotal = 30_000.0,
    canSell = true,
    canClose = true,
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
            piecesPerCarton = 12, pricePerCarton = 25_000.0, pieces = 48, onVehicle = 48, toDeliver = 48,
            toDeliverQty = qty(48, 12), amount = 100_000.0,
        ),
    ),
)

// A trip that carries an order as well as goods to sell at the stops.
@Preview
@Composable
private fun TripWithOrdersPreview() {
    SalesTheme {
        TripDetailScreen(
            TripDetailState(
                loading = false,
                trip = sampleTrip.copy(
                    orders = listOf(sampleOrder),
                    lines = sampleLines.map { line ->
                        if (line.productId != 1) line else line.copy(
                            forOrders = 48, forOrdersQty = qty(48, 12), forSale = 24, forSaleQty = qty(24, 12),
                        )
                    },
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun TripOnRoadPreview() {
    SalesTheme { TripDetailScreen(TripDetailState(loading = false, trip = sampleTrip), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun TripOutboxPreview() {
    SalesTheme {
        TripDetailScreen(
            TripDetailState(
                loading = false,
                trip = sampleTrip,
                fromPhone = true,
                outbox = TripOutbox(
                    tripId = 2,
                    sales = listOf(
                        com.salesapp.data.OutboxSale(
                            com.salesapp.data.TripSaleRequest(requestId = "a", customerId = 1, stop = "Nkhotakota", payment = "cash"),
                        ),
                        com.salesapp.data.OutboxSale(
                            com.salesapp.data.TripSaleRequest(requestId = "b", customerId = 1, stop = "Nkhotakota", payment = "cash"),
                            failed = "Only 2 ctn is on the van.",
                        ),
                    ),
                    expenses = listOf(
                        com.salesapp.data.OutboxExpense(
                            com.salesapp.data.TripExpenseRequest(requestId = "c", amount = 5_000, description = "Food allowance"),
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

// Sales this phone could not read, now with the office: the trip cannot close yet.
@Preview
@Composable
private fun TripOutboxProblemPreview() {
    SalesTheme {
        TripDetailScreen(
            TripDetailState(
                loading = false,
                hasUnreadable = true,
                trip = sampleTrip.copy(
                    outboxProblems = listOf(
                        com.salesapp.data.TripOutboxProblem(id = 5, requestId = "trip-2-outbox-unreadable-1.json"),
                    ),
                    openOutboxProblems = 1,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun TripUnreadablePreview() {
    SalesTheme {
        TripDetailScreen(
            TripDetailState(loading = false, trip = sampleTrip, hasUnreadable = true),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun TripToLoadPreview() {
    SalesTheme {
        TripDetailScreen(
            TripDetailState(
                loading = false,
                trip = sampleTrip.copy(status = "planned", canLoad = true, canSell = false, canClose = false, gatePassNo = null),
                loadLines = sampleLines.map { LoadLine(it.productId, it.label, it.piecesPerCarton, it.plannedQty.cartons, it.plannedQty.loosePieces) },
                vehicles = listOf(com.salesapp.data.Vehicle(id = 1, plate = "LL 5678", facilityCode = "F1")),
                loadVehicleId = 1,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun TripClosedPreview() {
    SalesTheme {
        TripDetailScreen(
            TripDetailState(
                loading = false,
                trip = sampleTrip.copy(
                    status = "closed", canSell = false, canClose = false,
                    cashExpected = 300_000.0, cashCounted = 299_975.0, cashDifference = -25.0,
                    closeNote = "One carton fell off at Salima",
                    lines = sampleLines.map { it.copy(returned = it.onVehicle - 12, missing = 12, onVehicle = 0) },
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
