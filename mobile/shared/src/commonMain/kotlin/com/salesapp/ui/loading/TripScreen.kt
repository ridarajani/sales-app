package com.salesapp.ui.loading

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.PlanDetail
import com.salesapp.data.PlanDrop
import com.salesapp.data.PlanDropLine
import com.salesapp.data.PlanProduct
import com.salesapp.data.User
import com.salesapp.resources.Res
import com.salesapp.resources.loading_trip_delivery_note
import com.salesapp.resources.loading_trip_driver
import com.salesapp.resources.loading_trip_drops
import com.salesapp.resources.loading_trip_gate_pass
import com.salesapp.resources.loading_trip_later
import com.salesapp.resources.loading_trip_left_at
import com.salesapp.resources.loading_trip_line_lpo
import com.salesapp.resources.loading_trip_no_drops
import com.salesapp.resources.loading_trip_orders
import com.salesapp.resources.loading_trip_products
import com.salesapp.resources.loading_trip_shop_total
import com.salesapp.resources.number_no_access
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import com.salesapp.ui.theme.sales
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TripPage(
    id: Int,
    onBack: () -> Unit,
    viewModel: TripViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Coming back to the screen shows the plan as it is now.
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(TripEvent.Refresh)
        onPauseOrDispose {}
    }
    TripScreen(state, viewModel::onEvent, onBack)
}

// One plan for the driver and for people who only look: the header, the shops to drop at in order
// and, for non-drivers, the product totals. No batches and no stock figures.
@Composable
fun TripScreen(state: TripState, onEvent: (TripEvent) -> Unit, onBack: () -> Unit) {
    val plan = state.plan
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(plan?.let { it.vehiclePlate?.takeIf { plate -> plate.isNotBlank() } ?: it.number } ?: "", onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.number_no_access), error = true, modifier = Modifier.padding(16.dp))
            plan != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(plan)
                    if (state.seesChanges) {
                        ChangesBanner(plan.changes, state.user, onSeen = { onEvent(TripEvent.Seen) }, busy = state.busy)
                    }
                    state.actionError?.let { Say(it, error = true) }
                    SectionLabel(stringResource(Res.string.loading_trip_drops), modifier = Modifier.padding(top = 4.dp))
                    if (plan.drops.isEmpty()) {
                        Say(stringResource(Res.string.loading_trip_no_drops))
                    } else {
                        plan.drops.forEachIndexed { index, drop -> DropCard(index + 1, drop) }
                    }
                    if (state.showsProducts && plan.products.isNotEmpty()) {
                        SectionLabel(stringResource(Res.string.loading_trip_products), modifier = Modifier.padding(top = 4.dp))
                        ListCard(onClick = null) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                plan.products.forEach { ProductRow(it, planned = plan.status == "planned") }
                            }
                        }
                    }
                    Text(
                        stringResource(Res.string.loading_trip_later),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
                    )
                }
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(TripEvent.Retry) })
            else -> LoadingState()
        }
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
            }
            plan.driverName?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.loading_trip_driver, it), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
            plan.deliveryNoteNo?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.loading_trip_delivery_note, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            plan.gatePassNo?.takeIf { it.isNotBlank() }?.let {
                Text(stringResource(Res.string.loading_trip_gate_pass, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            plan.outAt?.takeIf { it.isNotBlank() }?.let {
                Text(
                    stringResource(Res.string.loading_trip_left_at, formatIsoDate(localIsoDateFromUtcTimestamp(it))),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

// One shop: its number in the drop order, name, area, orders, what to hand over and the shop's total.
@Composable
private fun DropCard(number: Int, drop: PlanDrop) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    modifier = Modifier.sizeIn(minWidth = 28.dp, minHeight = 28.dp).clip(CircleShape).background(MaterialTheme.sales.goldSoft),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        number.toString(),
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.labelLarge,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 6.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(drop.customerName, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
                    if (drop.area.isNotBlank()) {
                        Text(drop.area, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                    if (drop.orders.isNotEmpty()) {
                        Text(
                            stringResource(Res.string.loading_trip_orders, drop.orders.joinToString(", ")),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.caption,
                        )
                    }
                }
            }
            drop.lines.forEach { DropLineRow(it) }
            Text(
                stringResource(Res.string.loading_trip_shop_total, cartonsText(drop.lines.sumOf { it.cartons }, drop.lines.sumOf { it.loosePieces })),
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun DropLineRow(line: PlanDropLine) {
    val scheme = MaterialTheme.colorScheme
    val amount = cartonsText(line.cartons, line.loosePieces)
    Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            listOf(line.brand, line.name, line.packSize).filter { it.isNotBlank() }.joinToString(" "),
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
        )
        val lpo = line.lpoNo?.takeIf { it.isNotBlank() }
        Text(
            if (lpo != null) stringResource(Res.string.loading_trip_line_lpo, amount, lpo) else amount,
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

// Loaded amount once the plan is loaded; the planned amount while it is still planned.
@Composable
private fun ProductRow(product: PlanProduct, planned: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOf(product.brand, product.name, product.packSize).filter { it.isNotBlank() }.joinToString(" "),
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )
        Text(
            if (planned) cartonsText(product.cartons, product.loosePieces) else cartonsText(product.loadedCartons.cartons, product.loadedCartons.loosePieces),
            color = scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

private val previewDriver = User(7, "Yamikani Banda", "0991000007", "driver")

private val previewTrip = PlanDetail(
    id = 3,
    number = "LP-000003",
    facilityCode = "F1",
    facilityName = "Facility 1",
    status = "out",
    sent = true,
    vehiclePlate = "BT 4521",
    driverName = "Yamikani Banda",
    deliveryNoteNo = "DN-000003",
    gatePassNo = "GP-000003",
    outAt = "2026-10-23T07:30:00.000Z",
    drops = listOf(
        PlanDrop(
            customerId = 1,
            customerName = "Area 47 Superstore",
            area = "Area 47",
            orders = listOf("SO-000012", "SO-000014"),
            lines = listOf(
                PlanDropLine("SO-000012", "LV", "LPO-5521", 1, "KG025", "Kukoma", "Cooking oil", "2 L", 120, 10, 0),
                PlanDropLine("SO-000014", "ZT", null, 3, "ZT-S1", "Zoona", "Sugar", "1 kg", 52, 2, 4),
            ),
        ),
        PlanDrop(
            customerId = 2,
            customerName = "Kanengo Wholesalers",
            area = "Kanengo",
            orders = listOf("SO-000015"),
            lines = listOf(PlanDropLine("SO-000015", "LV", null, 2, "KG010", "Kukoma", "Cooking oil", "1 L", 24, 1, 0)),
        ),
    ),
)

@Preview
@Composable
private fun TripDriverPreview() {
    SalesTheme {
        TripScreen(TripState(loading = false, plan = previewTrip, user = previewDriver), onEvent = {}, onBack = {})
    }
}
