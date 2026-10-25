package com.salesapp.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.data.Company
import com.salesapp.data.Driver
import com.salesapp.data.OpenOrder
import com.salesapp.data.Product
import com.salesapp.data.UserRow
import com.salesapp.data.Vehicle
import com.salesapp.resources.Res
import com.salesapp.resources.cancel
import com.salesapp.resources.plan_add_stop
import com.salesapp.resources.plan_cartons
import com.salesapp.resources.plan_change_title
import com.salesapp.resources.plan_company
import com.salesapp.resources.plan_date
import com.salesapp.resources.plan_delete
import com.salesapp.resources.plan_delete_text
import com.salesapp.resources.plan_delete_title
import com.salesapp.resources.plan_delete_yes
import com.salesapp.resources.plan_driver
import com.salesapp.resources.plan_float
import com.salesapp.resources.plan_kind
import com.salesapp.resources.plan_kind_minibus
import com.salesapp.resources.plan_kind_upcountry
import com.salesapp.resources.plan_leader
import com.salesapp.resources.plan_leader_help
import com.salesapp.resources.plan_no_access
import com.salesapp.resources.plan_no_stops
import com.salesapp.resources.plan_note
import com.salesapp.resources.plan_orders
import com.salesapp.resources.plan_orders_add
import com.salesapp.resources.plan_orders_chosen
import com.salesapp.resources.plan_orders_done
import com.salesapp.resources.plan_orders_help
import com.salesapp.resources.plan_orders_left
import com.salesapp.resources.plan_orders_none_chosen
import com.salesapp.resources.plan_orders_none_open
import com.salesapp.resources.plan_orders_pick
import com.salesapp.resources.plan_orders_pick_help
import com.salesapp.resources.plan_orders_products
import com.salesapp.resources.plan_orders_remove
import com.salesapp.resources.plan_orders_wanted
import com.salesapp.resources.plan_pieces
import com.salesapp.resources.plan_products
import com.salesapp.resources.plan_products_help
import com.salesapp.resources.plan_products_optional
import com.salesapp.resources.plan_route
import com.salesapp.resources.plan_route_hint
import com.salesapp.resources.plan_save
import com.salesapp.resources.plan_saving
import com.salesapp.resources.plan_staff
import com.salesapp.resources.plan_stop_hint
import com.salesapp.resources.plan_stops
import com.salesapp.resources.plan_title
import com.salesapp.resources.plan_vehicle
import com.salesapp.resources.plan_warehouse
import com.salesapp.ui.catalog.facilityLabel
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.ConfirmDialog
import com.salesapp.ui.components.DangerButton
import com.salesapp.ui.components.DateField
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TickCard
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.components.VehiclePicker
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PlanTripPage(
    tripId: Int?,
    onBack: () -> Unit,
    onSaved: (Int) -> Unit,
    onDeleted: () -> Unit,
    viewModel: PlanTripViewModel = koinViewModel { parametersOf(tripId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PlanTripEffect.Saved -> onSaved(effect.tripId)
                PlanTripEffect.Deleted -> onDeleted()
            }
        }
    }
    PlanTripScreen(state, viewModel::onEvent, onBack)
}

// A minibus route or an upcountry trip: where it goes, who leads it, and what goes on the van.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlanTripScreen(state: PlanTripState, onEvent: (PlanTripEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            stringResource(
                when {
                    state.picking -> Res.string.plan_orders_add
                    state.tripId == null -> Res.string.plan_title
                    else -> Res.string.plan_change_title
                },
            ),
            // While picking orders, back closes the list and leaves the form as it was.
            onBack = { if (state.picking) onEvent(PlanTripEvent.OrdersDone) else onBack() },
        )
        when {
            !state.allowed -> Say(stringResource(Res.string.plan_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(PlanTripEvent.Retry) })
            state.picking -> PickOrders(state, onEvent)
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionLabel(stringResource(Res.string.plan_kind))
                    ChoiceColumn {
                        listOf(
                            "upcountry" to stringResource(Res.string.plan_kind_upcountry),
                            "minibus" to stringResource(Res.string.plan_kind_minibus),
                        ).forEach { (code, label) ->
                            ChoiceButton(
                                text = label,
                                selected = state.kind == code,
                                onClick = { onEvent(PlanTripEvent.KindChosen(code)) },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    if (state.facilities.size > 1) {
                        SectionLabel(stringResource(Res.string.plan_warehouse))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.facilities.forEach { code ->
                                ChoiceButton(
                                    text = facilityLabel(code),
                                    selected = state.facilityCode == code,
                                    onClick = { onEvent(PlanTripEvent.FacilityChosen(code)) },
                                    enabled = !state.busy,
                                )
                            }
                        }
                    }
                    if (state.facilityCompanies.size > 1) {
                        SectionLabel(stringResource(Res.string.plan_company))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.facilityCompanies.forEach { company ->
                                ChoiceButton(
                                    text = company.name,
                                    selected = state.companyCode == company.code,
                                    onClick = { onEvent(PlanTripEvent.CompanyChosen(company.code)) },
                                    enabled = !state.busy,
                                )
                            }
                        }
                    }
                    DateField(
                        label = stringResource(Res.string.plan_date),
                        isoDate = state.tripDate,
                        onDateChange = { onEvent(PlanTripEvent.DateChanged(it)) },
                    )
                    Field(
                        label = stringResource(Res.string.plan_route),
                        value = state.route,
                        onValueChange = { onEvent(PlanTripEvent.RouteChanged(it)) },
                        placeholder = stringResource(Res.string.plan_route_hint),
                        enabled = !state.busy,
                    )
                    Stops(state, onEvent)
                    if (state.vehicles.isNotEmpty()) {
                        SectionLabel(stringResource(Res.string.plan_vehicle))
                        VehiclePicker(
                            vehicles = state.vehicles,
                            chosenId = state.vehicleId,
                            onChoose = { onEvent(PlanTripEvent.VehicleChosen(it)) },
                            enabled = !state.busy,
                        )
                    }
                    if (state.drivers.isNotEmpty()) {
                        SectionLabel(stringResource(Res.string.plan_driver))
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            state.drivers.forEach { driver ->
                                ChoiceButton(
                                    text = driver.name,
                                    selected = state.driverId == driver.id,
                                    onClick = { onEvent(PlanTripEvent.DriverChosen(driver.id)) },
                                    enabled = !state.busy,
                                )
                            }
                        }
                    }
                    SectionLabel(stringResource(Res.string.plan_leader))
                    Text(
                        stringResource(Res.string.plan_leader_help),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChoiceColumn {
                        state.leaders.forEach { leader ->
                            ChoiceButton(
                                text = leader.name,
                                selected = state.leaderId == leader.id,
                                onClick = { onEvent(PlanTripEvent.LeaderChosen(leader.id)) },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    Field(
                        label = stringResource(Res.string.plan_staff),
                        value = state.staff,
                        onValueChange = { onEvent(PlanTripEvent.StaffChanged(it)) },
                        enabled = !state.busy,
                    )
                    MwkField(
                        label = stringResource(Res.string.plan_float),
                        amount = state.float,
                        onAmountChange = { onEvent(PlanTripEvent.FloatChanged(it)) },
                    )
                    Field(
                        label = stringResource(Res.string.plan_note),
                        value = state.note,
                        onValueChange = { onEvent(PlanTripEvent.NoteChanged(it)) },
                        enabled = !state.busy,
                    )
                    Orders(state, onEvent)
                    SectionLabel(stringResource(Res.string.plan_products))
                    Text(
                        stringResource(if (state.hasOrders) Res.string.plan_products_optional else Res.string.plan_products_help),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.lines.forEach { line -> LineCard(line, state.busy, onEvent) }
                    state.actionError?.let { Say(it, error = true) }
                    if (state.tripId != null) {
                        DangerButton(
                            stringResource(Res.string.plan_delete),
                            onClick = { onEvent(PlanTripEvent.AskDelete) },
                            enabled = !state.busy,
                        )
                    }
                }
                PrimaryButton(
                    stringResource(if (state.busy) Res.string.plan_saving else Res.string.plan_save),
                    onClick = { onEvent(PlanTripEvent.Save) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
    if (state.asking) {
        ConfirmDialog(
            title = stringResource(Res.string.plan_delete_title),
            text = stringResource(Res.string.plan_delete_text),
            confirmLabel = stringResource(Res.string.plan_delete_yes),
            onConfirm = { onEvent(PlanTripEvent.Delete) },
            onDismiss = { onEvent(PlanTripEvent.Dismiss) },
            danger = true,
        )
    }
}

// Stops are added one at a time, so a long route is easy to type on a phone.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Stops(state: PlanTripState, onEvent: (PlanTripEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.plan_stops))
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Field(
                label = stringResource(Res.string.plan_stop_hint),
                value = state.stopDraft,
                onValueChange = { onEvent(PlanTripEvent.StopDraftChanged(it)) },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                stringResource(Res.string.plan_add_stop),
                onClick = { onEvent(PlanTripEvent.AddStop) },
                enabled = !state.busy && state.stopDraft.isNotBlank(),
                modifier = Modifier.weight(0.4f),
            )
        }
        if (state.stops.isEmpty()) {
            Text(stringResource(Res.string.plan_no_stops), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        } else {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.stops.forEach { stop ->
                    // Tapping a stop takes it off again.
                    ChoiceButton(
                        text = stop,
                        selected = true,
                        onClick = { onEvent(PlanTripEvent.RemoveStop(stop)) },
                        enabled = !state.busy,
                    )
                }
            }
        }
    }
}

// Orders booked ahead that this trip takes out, each with a way to take it off again.
@Composable
private fun Orders(state: PlanTripState, onEvent: (PlanTripEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel(stringResource(Res.string.plan_orders))
        Text(stringResource(Res.string.plan_orders_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (state.chosenOrders.isEmpty()) {
            Text(stringResource(Res.string.plan_orders_none_chosen), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        }
        state.chosenOrders.forEach { order ->
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) { OrderFacts(order) }
                SecondaryButton(
                    stringResource(Res.string.plan_orders_remove),
                    onClick = { onEvent(PlanTripEvent.RemoveOrder(order.id)) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(0.6f),
                )
            }
        }
        SecondaryButton(
            stringResource(Res.string.plan_orders_add),
            onClick = { onEvent(PlanTripEvent.AddOrders) },
            enabled = !state.busy,
        )
    }
}

// The list of orders this warehouse could send out, ticked one by one.
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PickOrders(state: PlanTripState, onEvent: (PlanTripEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(stringResource(Res.string.plan_orders_pick))
        Text(stringResource(Res.string.plan_orders_pick_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        if (state.hasOrders) {
            Text(
                stringResource(Res.string.plan_orders_chosen, state.orderIds.size),
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.openOrders.isEmpty()) {
            item { Say(stringResource(Res.string.plan_orders_none_open)) }
        }
        items(state.openOrders, key = { it.id }) { order ->
            TickCard(
                checked = order.id in state.orderIds,
                onCheckedChange = { onEvent(PlanTripEvent.OrderToggled(order.id)) },
                enabled = !state.busy,
            ) {
                OrderFacts(order)
            }
        }
    }
    PrimaryButton(
        stringResource(Res.string.plan_orders_done),
        onClick = { onEvent(PlanTripEvent.OrdersDone) },
        modifier = Modifier.padding(16.dp),
    )
}

// What an order says, the same way in the list and on the card.
@Composable
private fun OrderFacts(order: OpenOrder) {
    val scheme = MaterialTheme.colorScheme
    Text(order.customerName, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
    Text(order.number, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    if (order.area.isNotBlank()) {
        Text(order.area, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
    if (order.wantedOn.isNotBlank()) {
        Text(
            stringResource(Res.string.plan_orders_wanted, formatIsoDate(order.wantedOn)),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
    Text(
        stringResource(Res.string.plan_orders_left, formatMwk(order.amountLeft.toLong())),
        color = scheme.onSurface,
        style = MaterialTheme.typography.bodySmall,
    )
    if (order.products > 0) {
        Text(
            stringResource(Res.string.plan_orders_products, order.products),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun LineCard(line: PlanLine, busy: Boolean, onEvent: (PlanTripEvent) -> Unit) {
    val productId = line.product.id
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                line.label,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    label = stringResource(Res.string.plan_cartons),
                    value = line.cartons,
                    onValueChange = { onEvent(PlanTripEvent.EditCartons(productId, it)) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                if (line.perCarton > 1) {
                    NumberField(
                        label = stringResource(Res.string.plan_pieces),
                        value = line.pieces,
                        onValueChange = { onEvent(PlanTripEvent.EditPieces(productId, it)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

private val sampleProducts = listOf(
    Product(id = 1, code = "LV-MILK", companyCode = "LV", brand = "Lucky", name = "Milk", packSize = "500 ml", piecesPerCarton = 12),
    Product(id = 2, code = "ZT-TEA", companyCode = "ZT", brand = "Zoona", name = "Tea", packSize = "sachet", piecesPerCarton = 40),
)

private fun samplePlan(tripId: Int? = null) = PlanTripState(
    loading = false,
    tripId = tripId,
    facilities = listOf("F1", "F2"),
    facilityCode = "F1",
    companies = listOf(Company("LV", "Lucky Ventures", "F1"), Company("ZT", "Zoona Trading", "F1")),
    companyCode = "LV",
    tripDate = "2026-10-26",
    route = "Salima and Nkhotakota",
    stops = listOf("Salima market", "Nkhotakota"),
    vehicles = listOf(Vehicle(id = 1, plate = "LL 5678", description = "7 tonne", facilityCode = "F1", defaultDriverId = 7)),
    vehicleId = 1,
    drivers = listOf(Driver(id = 7, name = "Kondwani Banda", phone = "0991000007")),
    driverId = 7,
    leaders = listOf(
        UserRow(id = 4, name = "Chimwemwe Banda", phone = "0991000004", role = "salesman", companies = listOf("LV", "ZT")),
        UserRow(id = 3, name = "Mr. Abbas", phone = "0991000003", role = "sales_manager", companies = listOf("LV", "ZT")),
    ),
    leaderId = 4,
    staff = "Chimwemwe, T. Mwale",
    float = 100_000,
    lines = listOf(PlanLine(sampleProducts[0], cartons = 10), PlanLine(sampleProducts[1], cartons = 5)),
)

private val sampleOpenOrders = listOf(
    OpenOrder(
        id = 31, number = "LV-ORD-000031", customerId = 1, customerName = "Salima Shop", area = "Salima",
        channel = "upcountry", wantedOn = "2026-10-27", amountLeft = 450_000.0, products = 3,
    ),
    OpenOrder(
        id = 32, number = "ZT-ORD-000032", customerId = 2, customerName = "Nkhotakota Stores", area = "Nkhotakota",
        channel = "upcountry", wantedOn = "2026-10-28", amountLeft = 120_000.0, products = 1,
    ),
)

@Preview
@Composable
private fun PlanTripWithOrdersPreview() {
    SalesTheme {
        PlanTripScreen(
            samplePlan().copy(openOrders = sampleOpenOrders, orderIds = setOf(31)),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlanTripPickOrdersPreview() {
    SalesTheme {
        PlanTripScreen(
            samplePlan().copy(openOrders = sampleOpenOrders, orderIds = setOf(31), picking = true),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun PlanTripPreview() {
    SalesTheme { PlanTripScreen(samplePlan(), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun PlanTripChangePreview() {
    SalesTheme { PlanTripScreen(samplePlan(tripId = 2), onEvent = {}, onBack = {}) }
}
