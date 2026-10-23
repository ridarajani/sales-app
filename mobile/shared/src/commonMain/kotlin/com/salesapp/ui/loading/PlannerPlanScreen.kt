package com.salesapp.ui.loading

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.Driver
import com.salesapp.data.OpenLine
import com.salesapp.data.OpenProduct
import com.salesapp.data.PieceSplit
import com.salesapp.data.PlanChange
import com.salesapp.data.PlanDetail
import com.salesapp.data.PlanItem
import com.salesapp.data.PlanProduct
import com.salesapp.data.User
import com.salesapp.data.Vehicle
import com.salesapp.resources.Res
import com.salesapp.resources.loading_change_amount
import com.salesapp.resources.loading_change_by
import com.salesapp.resources.loading_change_reason
import com.salesapp.resources.loading_pl_all_on
import com.salesapp.resources.loading_pl_can_go
import com.salesapp.resources.loading_pl_cartons
import com.salesapp.resources.loading_pl_delete
import com.salesapp.resources.loading_pl_delete_text
import com.salesapp.resources.loading_pl_delete_title
import com.salesapp.resources.loading_pl_delete_yes
import com.salesapp.resources.loading_pl_driver
import com.salesapp.resources.loading_pl_left_out
import com.salesapp.resources.loading_pl_left_out_help
import com.salesapp.resources.loading_pl_left_out_row
import com.salesapp.resources.loading_pl_lpo
import com.salesapp.resources.loading_pl_need_orders
import com.salesapp.resources.loading_pl_need_vehicle
import com.salesapp.resources.loading_pl_no_access
import com.salesapp.resources.loading_pl_no_driver
import com.salesapp.resources.loading_pl_no_open
import com.salesapp.resources.loading_pl_no_vehicle
import com.salesapp.resources.loading_pl_not_sent_help
import com.salesapp.resources.loading_pl_nothing_on
import com.salesapp.resources.loading_pl_on_vehicle
import com.salesapp.resources.loading_pl_open_help
import com.salesapp.resources.loading_pl_open_orders
import com.salesapp.resources.loading_pl_order_shop
import com.salesapp.resources.loading_pl_pieces
import com.salesapp.resources.loading_pl_save
import com.salesapp.resources.loading_pl_save_changes
import com.salesapp.resources.loading_pl_saving
import com.salesapp.resources.loading_pl_send
import com.salesapp.resources.loading_pl_send_text
import com.salesapp.resources.loading_pl_send_title
import com.salesapp.resources.loading_pl_send_yes
import com.salesapp.resources.loading_pl_sent_at
import com.salesapp.resources.loading_pl_sent_help
import com.salesapp.resources.loading_pl_take_off
import com.salesapp.resources.loading_pl_total_one_shop
import com.salesapp.resources.loading_pl_total_shops
import com.salesapp.resources.loading_pl_vehicle
import com.salesapp.resources.loading_pl_vehicle_with
import com.salesapp.resources.loading_pl_wh_changes
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ConfirmDialog
import com.salesapp.ui.components.DangerButton
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.NumberField
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
fun PlannerPlanPage(
    id: Int,
    onBack: () -> Unit,
    onDeleted: () -> Unit,
    onOpen: (Any) -> Unit,
    viewModel: PlannerPlanViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                PlannerPlanEffect.Deleted -> onDeleted()
                is PlannerPlanEffect.Open -> onOpen(effect.route)
            }
        }
    }
    // Coming back shows the plan as it is now, unless something is half typed.
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(PlannerPlanEvent.Refresh)
        onPauseOrDispose {}
    }
    PlannerPlanScreen(state, viewModel::onEvent, onBack)
}

// One plan as the planner makes it: the vehicle and driver, what is on the vehicle now, the open
// order lines to put on, the warehouse's changes, then Send to the warehouse.
@Composable
fun PlannerPlanScreen(state: PlannerPlanState, onEvent: (PlannerPlanEvent) -> Unit, onBack: () -> Unit) {
    val plan = state.plan
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(plan?.let { it.vehiclePlate?.takeIf { plate -> plate.isNotBlank() } ?: it.number } ?: "", onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.loading_pl_no_access), error = true, modifier = Modifier.padding(16.dp))
            plan != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(plan)
                    // The planner hears about the warehouse's changes and says "OK, seen".
                    ChangesBanner(plan.changes, state.user, onSeen = { onEvent(PlannerPlanEvent.Seen) }, busy = state.busy)
                    if (plan.canPlan) {
                        Say(stringResource(if (plan.sent) Res.string.loading_pl_sent_help else Res.string.loading_pl_not_sent_help))
                        VehicleAndDriver(state, onEvent)
                    }
                    OnThisVehicle(plan)
                    LeftOut(state, onEvent)
                    if (plan.canPlan) OpenOrders(state, onEvent)
                    WarehouseChanges(plan)
                    state.actionError?.let { Say(it, error = true) }
                }
                if (plan.canPlan) Actions(state, plan, onEvent)
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(PlannerPlanEvent.Retry) })
            else -> LoadingState()
        }
    }
    when (state.asking) {
        PlannerAsk.Send -> ConfirmDialog(
            title = stringResource(Res.string.loading_pl_send_title),
            text = stringResource(Res.string.loading_pl_send_text),
            confirmLabel = stringResource(Res.string.loading_pl_send_yes),
            onConfirm = { onEvent(PlannerPlanEvent.Confirm) },
            onDismiss = { onEvent(PlannerPlanEvent.Dismiss) },
        )
        PlannerAsk.Delete -> ConfirmDialog(
            title = stringResource(Res.string.loading_pl_delete_title),
            text = stringResource(Res.string.loading_pl_delete_text),
            confirmLabel = stringResource(Res.string.loading_pl_delete_yes),
            onConfirm = { onEvent(PlannerPlanEvent.Confirm) },
            onDismiss = { onEvent(PlannerPlanEvent.Dismiss) },
            danger = true,
        )
        null -> Unit
    }
}

@Composable
private fun Header(plan: PlanDetail) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(plan.number, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f, fill = false))
                PlanStatusBadge(plan.status)
                if (plan.status == "planned") SentBadge(plan.sent)
            }
            plan.sentAt?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(Res.string.loading_pl_sent_at, formatIsoDate(localIsoDateFromUtcTimestamp(it))),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// The vehicle and the driver, as choice buttons. Picking a vehicle brings its usual driver along.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun VehicleAndDriver(state: PlannerPlanState, onEvent: (PlannerPlanEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.loading_pl_vehicle))
        if (state.vehicles.isEmpty()) {
            Say(stringResource(Res.string.loading_pl_no_vehicle))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.vehicles.forEach { vehicle ->
                    ChoiceButton(
                        text = if (vehicle.description.isBlank()) {
                            vehicle.plate
                        } else {
                            stringResource(Res.string.loading_pl_vehicle_with, vehicle.plate, vehicle.description)
                        },
                        selected = state.chosenVehicle == vehicle.id,
                        onClick = { onEvent(PlannerPlanEvent.VehicleChosen(vehicle.id)) },
                        enabled = !state.busy,
                    )
                }
            }
        }
        SectionLabel(stringResource(Res.string.loading_pl_driver))
        if (state.drivers.isEmpty()) {
            Say(stringResource(Res.string.loading_pl_no_driver))
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.drivers.forEach { driver ->
                    ChoiceButton(
                        text = driver.name,
                        selected = state.chosenDriver == driver.id,
                        onClick = { onEvent(PlannerPlanEvent.DriverChosen(driver.id)) },
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

// What the plan carries now, product by product, with the shop count.
@Composable
private fun OnThisVehicle(plan: PlanDetail) {
    val scheme = MaterialTheme.colorScheme
    val onPlan = plan.products.filter { it.pieces > 0 }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.loading_pl_on_vehicle))
        if (onPlan.isEmpty()) {
            Say(stringResource(Res.string.loading_pl_nothing_on))
            return@Column
        }
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                onPlan.forEach { product ->
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(productLabel(product), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            cartonsText(product.cartons, product.loosePieces),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                val shops = onPlan.flatMap { it.items }.map { it.customerId }.distinct().size
                val total = cartonsText(onPlan.sumOf { it.cartons }, onPlan.sumOf { it.loosePieces })
                Text(
                    if (shops == 1) {
                        stringResource(Res.string.loading_pl_total_one_shop, total)
                    } else {
                        stringResource(Res.string.loading_pl_total_shops, total, shops)
                    },
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
    }
}

// Lines still on the plan whose order has gone on hold or closed: they have to come off.
@Composable
private fun LeftOut(state: PlannerPlanState, onEvent: (PlannerPlanEvent) -> Unit) {
    val rows = state.leftOut
    if (rows.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.loading_pl_left_out))
        Say(stringResource(Res.string.loading_pl_left_out_help), error = true)
        rows.forEach { item ->
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        stringResource(Res.string.loading_pl_left_out_row, item.customerName, cartonsText(item.cartons, item.loosePieces)),
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(item.orderNumber, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                    SecondaryButton(
                        stringResource(Res.string.loading_pl_take_off),
                        onClick = { onEvent(PlannerPlanEvent.TakeOff(item.lineId)) },
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

// The open order lines, grouped by product: one row per shop with what can still go on.
@Composable
private fun OpenOrders(state: PlannerPlanState, onEvent: (PlannerPlanEvent) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.loading_pl_open_orders))
        if (state.open.isEmpty()) {
            Say(stringResource(Res.string.loading_pl_no_open))
            return@Column
        }
        Text(
            stringResource(Res.string.loading_pl_open_help),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        state.open.forEach { product -> OpenProductCard(product, state, onEvent) }
    }
}

@Composable
private fun OpenProductCard(product: OpenProduct, state: PlannerPlanState, onEvent: (PlannerPlanEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                listOf(product.brand, product.name, product.packSize).filter { it.isNotBlank() }.joinToString(" "),
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
            product.lines.forEach { line -> OpenLineRow(line, product, state, onEvent) }
            SecondaryButton(
                stringResource(Res.string.loading_pl_all_on),
                onClick = { onEvent(PlannerPlanEvent.AllOn(product.productId)) },
                enabled = !state.busy,
            )
        }
    }
}

// One shop's line: who it is for, how much can still go, and the cartons and pieces to put on.
@Composable
private fun OpenLineRow(line: OpenLine, product: OpenProduct, state: PlannerPlanState, onEvent: (PlannerPlanEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val picked = state.pickedFor(line)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            stringResource(Res.string.loading_pl_order_shop, line.customerName, line.orderNumber),
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp)
                .clickable(enabled = !state.busy, role = Role.Button) { onEvent(PlannerPlanEvent.OpenOrder(line.orderId)) },
        )
        val lpo = line.lpoNo?.takeIf { it.isNotBlank() }
        Text(
            listOfNotNull(
                stringResource(Res.string.loading_pl_can_go, cartonsText(line.openCartons.cartons, line.openCartons.loosePieces)),
                lpo?.let { stringResource(Res.string.loading_pl_lpo, it) },
                line.area.takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                label = stringResource(Res.string.loading_pl_cartons),
                value = picked.cartons,
                onValueChange = { onEvent(PlannerPlanEvent.EditCartons(line.lineId, it)) },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
            if (product.piecesPerCarton > 1) {
                NumberField(
                    label = stringResource(Res.string.loading_pl_pieces),
                    value = picked.pieces,
                    onValueChange = { onEvent(PlannerPlanEvent.EditPieces(line.lineId, it)) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

// Every change the warehouse made, with the reason they gave.
@Composable
private fun WarehouseChanges(plan: PlanDetail) {
    val changes = plan.changes.filter { it.byWarehouse }
    if (changes.isEmpty()) return
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.loading_pl_wh_changes))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                changes.forEach { change ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(
                                Res.string.loading_change_amount,
                                change.product,
                                splitText(change.before, change.piecesPerCarton),
                                splitText(change.after, change.piecesPerCarton),
                            ),
                            color = scheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (change.reason.isNotBlank()) {
                            Text(
                                stringResource(Res.string.loading_change_reason, change.reason),
                                color = scheme.onSurface,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Text(
                            stringResource(Res.string.loading_change_by, change.by, formatIsoDate(localIsoDateFromUtcTimestamp(change.at))),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.caption,
                        )
                    }
                }
            }
        }
    }
}

// Save, Send to the warehouse, and Delete plan while the plan is still planned.
@Composable
private fun Actions(state: PlannerPlanState, plan: PlanDetail, onEvent: (PlannerPlanEvent) -> Unit) {
    val ready = state.chosenVehicle != null && state.chosenDriver != null
    val why = when {
        !ready -> stringResource(Res.string.loading_pl_need_vehicle)
        !state.hasItems && state.picked.isEmpty() -> stringResource(Res.string.loading_pl_need_orders)
        else -> null
    }
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan.canSend && why != null) {
            Text(why, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        if (plan.canSend) {
            // Sending saves what is on screen first, so Save is only for keeping it for later.
            SecondaryButton(
                stringResource(if (state.busy) Res.string.loading_pl_saving else Res.string.loading_pl_save),
                onClick = { onEvent(PlannerPlanEvent.Save) },
                enabled = !state.busy && state.dirty,
            )
            PrimaryButton(
                stringResource(Res.string.loading_pl_send),
                onClick = { onEvent(PlannerPlanEvent.Ask(PlannerAsk.Send)) },
                enabled = !state.busy && why == null,
            )
        } else {
            // Already with the warehouse: changes are saved as they are made.
            PrimaryButton(
                stringResource(if (state.busy) Res.string.loading_pl_saving else Res.string.loading_pl_save_changes),
                onClick = { onEvent(PlannerPlanEvent.Save) },
                enabled = !state.busy && state.dirty,
            )
        }
        if (plan.canDelete) {
            DangerButton(
                stringResource(Res.string.loading_pl_delete),
                onClick = { onEvent(PlannerPlanEvent.Ask(PlannerAsk.Delete)) },
                enabled = !state.busy,
            )
        }
    }
}

private val previewPlanner = User(2, "Mr. Abbas", "0991000002", "sales_manager")

private val previewVehicles = listOf(
    Vehicle(id = 1, plate = "LL 4521", description = "7 tonne", facilityCode = "F1", defaultDriverId = 7, defaultDriverName = "Kondwani Banda"),
    Vehicle(id = 2, plate = "BT 9902", description = "3 tonne", facilityCode = "F1"),
)

private val previewDrivers = listOf(
    Driver(id = 7, name = "Kondwani Banda", phone = "0991000007"),
    Driver(id = 8, name = "Chisomo Phiri", phone = "0991000008"),
)

private val previewOpen = listOf(
    OpenProduct(
        productId = 1,
        code = "LV-MILK",
        brand = "Lucky",
        name = "Milk",
        packSize = "500 ml",
        companyCode = "LV",
        piecesPerCarton = 12,
        open = 96,
        current = 48,
        lines = listOf(
            OpenLine(
                lineId = 11, orderId = 1, orderNumber = "SO-000012", customerId = 1, customerName = "Area 47 Superstore",
                area = "Area 47", lpoNo = "LPO-5521", open = 48, current = 24,
                openCartons = PieceSplit(pieces = 48, cartons = 4), currentCartons = PieceSplit(pieces = 24, cartons = 2),
            ),
            OpenLine(
                lineId = 12, orderId = 2, orderNumber = "SO-000014", customerId = 2, customerName = "Kanengo Wholesalers",
                area = "Kanengo", open = 48, current = 24,
                openCartons = PieceSplit(pieces = 48, cartons = 4), currentCartons = PieceSplit(pieces = 24, cartons = 2),
            ),
        ),
    ),
    OpenProduct(
        productId = 2,
        code = "ZT-TEA",
        brand = "Zoona",
        name = "Tea",
        packSize = "sachet",
        companyCode = "ZT",
        piecesPerCarton = 40,
        open = 80,
        current = 0,
        lines = listOf(
            OpenLine(
                lineId = 13, orderId = 3, orderNumber = "SO-000015", customerId = 1, customerName = "Area 47 Superstore",
                area = "Area 47", open = 80, current = 0, openCartons = PieceSplit(pieces = 80, cartons = 2),
            ),
        ),
    ),
)

private val previewOnPlan = listOf(
    PlanProduct(
        productId = 1, code = "LV-MILK", brand = "Lucky", name = "Milk", packSize = "500 ml", companyCode = "LV",
        piecesPerCarton = 12, pieces = 48, cartons = 4,
        items = listOf(
            PlanItem(itemId = 1, lineId = 11, orderNumber = "SO-000012", customerId = 1, customerName = "Area 47 Superstore", pieces = 24, cartons = 2),
            PlanItem(itemId = 2, lineId = 12, orderNumber = "SO-000014", customerId = 2, customerName = "Kanengo Wholesalers", pieces = 24, cartons = 2),
        ),
    ),
)

private val previewPlan = PlanDetail(
    id = 5,
    number = "LP-000005",
    facilityCode = "F1",
    facilityName = "Facility 1",
    status = "planned",
    sent = false,
    vehicleId = 1,
    vehiclePlate = "LL 4521",
    driverId = 7,
    driverName = "Kondwani Banda",
    products = previewOnPlan,
    canPlan = true,
    canSend = true,
    canDelete = true,
)

@Preview
@Composable
private fun PlannerPlanPreview() {
    SalesTheme {
        PlannerPlanScreen(
            state = PlannerPlanState(
                loading = false,
                plan = previewPlan,
                open = previewOpen,
                vehicles = previewVehicles,
                drivers = previewDrivers,
                user = previewPlanner,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlannerPlanSentPreview() {
    SalesTheme {
        PlannerPlanScreen(
            state = PlannerPlanState(
                loading = false,
                plan = previewPlan.copy(
                    sent = true,
                    sentAt = "2026-10-23T08:15:00.000Z",
                    canSend = false,
                    changes = listOf(
                        PlanChange(
                            id = 1, byWarehouse = true, by = "Peter Phiri", at = "2026-10-23T10:00:00.000Z", productId = 1,
                            product = "Lucky Milk 500 ml", piecesPerCarton = 12, before = 48, after = 36,
                            reason = "Truck is full", seen = true,
                        ),
                    ),
                ),
                open = previewOpen,
                vehicles = previewVehicles,
                drivers = previewDrivers,
                user = previewPlanner,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlannerPlanLeftOutPreview() {
    SalesTheme {
        PlannerPlanScreen(
            state = PlannerPlanState(
                loading = false,
                plan = previewPlan,
                // The second shop's order went on hold, so its line is no longer open.
                open = listOf(previewOpen[0].copy(lines = previewOpen[0].lines.take(1))),
                vehicles = previewVehicles,
                drivers = previewDrivers,
                user = previewPlanner,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
