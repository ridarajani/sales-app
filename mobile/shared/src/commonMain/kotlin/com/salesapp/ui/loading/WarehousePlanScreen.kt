package com.salesapp.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.salesapp.data.NotLoaded
import com.salesapp.data.PlanBatch
import com.salesapp.data.PlanChange
import com.salesapp.data.PlanDetail
import com.salesapp.data.PlanItem
import com.salesapp.data.PieceSplit
import com.salesapp.data.PlanProduct
import com.salesapp.data.User
import com.salesapp.resources.Res
import com.salesapp.resources.cancel
import com.salesapp.resources.loading_wh_batch
import com.salesapp.resources.loading_wh_batches_loaded
import com.salesapp.resources.loading_wh_batches_to_pick
import com.salesapp.resources.loading_wh_cartons
import com.salesapp.resources.loading_wh_change
import com.salesapp.resources.loading_wh_delivery_note
import com.salesapp.resources.loading_wh_empty
import com.salesapp.resources.loading_wh_for_one_shop
import com.salesapp.resources.loading_wh_for_shops
import com.salesapp.resources.loading_wh_gate
import com.salesapp.resources.loading_wh_gate_pass
import com.salesapp.resources.loading_wh_gate_text
import com.salesapp.resources.loading_wh_gate_title
import com.salesapp.resources.loading_wh_gate_yes
import com.salesapp.resources.loading_wh_help
import com.salesapp.resources.loading_wh_left
import com.salesapp.resources.loading_wh_load_amount
import com.salesapp.resources.loading_wh_load_text
import com.salesapp.resources.loading_wh_load_title
import com.salesapp.resources.loading_wh_load_yes
import com.salesapp.resources.loading_wh_loaded
import com.salesapp.resources.loading_wh_loaded_amount
import com.salesapp.resources.loading_wh_no_access
import com.salesapp.resources.loading_wh_no_driver
import com.salesapp.resources.loading_wh_no_vehicle
import com.salesapp.resources.loading_wh_none_set_aside
import com.salesapp.resources.loading_wh_not_fit
import com.salesapp.resources.loading_wh_not_loaded
import com.salesapp.resources.loading_wh_not_loaded_row
import com.salesapp.resources.loading_wh_not_sent
import com.salesapp.resources.loading_wh_pieces
import com.salesapp.resources.loading_wh_planned_now
import com.salesapp.resources.loading_wh_reason
import com.salesapp.resources.loading_wh_save_change
import com.salesapp.resources.loading_wh_short
import com.salesapp.resources.loading_wh_vehicle
import com.salesapp.ui.components.ConfirmDialog
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WarehousePlanPage(
    id: Int,
    onBack: () -> Unit,
    viewModel: WarehousePlanViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // Coming back to the screen shows the plan as the planner has left it.
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(WarehousePlanEvent.Refresh)
        onPauseOrDispose {}
    }
    WarehousePlanScreen(state, viewModel::onEvent, onBack)
}

// One plan as the warehouse sees it: the products to pick with their batches, "Change cartons" with a
// reason, then Loaded and, once the delivery note is made, Vehicle left the gate.
@Composable
fun WarehousePlanScreen(state: WarehousePlanState, onEvent: (WarehousePlanEvent) -> Unit, onBack: () -> Unit) {
    val plan = state.plan
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(plan?.let { it.vehiclePlate?.takeIf { plate -> plate.isNotBlank() } ?: it.number } ?: "", onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.loading_wh_no_access), error = true, modifier = Modifier.padding(16.dp))
            plan != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Header(plan)
                    // The warehouse hears about the planner's changes and says "OK, seen".
                    ChangesBanner(plan.changes, state.user, onSeen = { onEvent(WarehousePlanEvent.Seen) }, busy = state.busy)
                    if (plan.status == "planned" && !plan.sent) {
                        Say(stringResource(Res.string.loading_wh_not_sent))
                    } else {
                        if (plan.canWarehouseChange) Say(stringResource(Res.string.loading_wh_help))
                        state.notFit.forEach { missing ->
                            Say(
                                stringResource(
                                    Res.string.loading_wh_not_fit,
                                    missing.label,
                                    splitText(missing.pieces, missing.piecesPerCarton),
                                ),
                                error = true,
                            )
                        }
                        if (plan.products.isEmpty()) {
                            Say(stringResource(Res.string.loading_wh_empty))
                        } else {
                            plan.products.forEach { product ->
                                ProductCard(
                                    product = product,
                                    planned = plan.status == "planned",
                                    canChange = plan.canWarehouseChange && !state.busy,
                                    onChange = { onEvent(WarehousePlanEvent.StartEdit(product.productId)) },
                                )
                            }
                        }
                        NotLoadedCard(state.notLoaded)
                        DocumentNumbers(plan)
                        if (state.hasDocuments) {
                            VehicleDocuments(
                                busy = state.busy,
                                onDeliveryNote = { onEvent(WarehousePlanEvent.DeliveryNote) },
                                onGatePass = { onEvent(WarehousePlanEvent.GatePass) },
                            )
                        }
                        // Admin is an invoice maker and sees this screen while the vehicle is loaded.
                        MakeInvoices(
                            canMake = plan.canMakeInvoices,
                            busy = state.busy,
                            made = state.made,
                            onMake = { onEvent(WarehousePlanEvent.MakeInvoices) },
                        )
                        if (plan.status == "out" || plan.status == "done") Say(stringResource(Res.string.loading_wh_left))
                    }
                    state.actionError?.let { Say(it, error = true) }
                }
                MainAction(state, plan, onEvent)
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(WarehousePlanEvent.Retry) })
            else -> LoadingState()
        }
    }
    state.edit?.let { edit -> ChangeDialog(edit, busy = state.busy, onEvent = onEvent) }
    when (state.asking) {
        WarehouseAsk.Load -> ConfirmDialog(
            title = stringResource(Res.string.loading_wh_load_title),
            text = stringResource(Res.string.loading_wh_load_text),
            confirmLabel = stringResource(Res.string.loading_wh_load_yes),
            onConfirm = { onEvent(WarehousePlanEvent.Confirm) },
            onDismiss = { onEvent(WarehousePlanEvent.Dismiss) },
        )
        WarehouseAsk.Gate -> ConfirmDialog(
            title = stringResource(Res.string.loading_wh_gate_title),
            text = stringResource(Res.string.loading_wh_gate_text),
            confirmLabel = stringResource(Res.string.loading_wh_gate_yes),
            onConfirm = { onEvent(WarehousePlanEvent.Confirm) },
            onDismiss = { onEvent(WarehousePlanEvent.Dismiss) },
        )
        null -> Unit
    }
}

// Loaded while the plan is still planned; once it is loaded, the vehicle can leave the gate.
@Composable
private fun MainAction(state: WarehousePlanState, plan: PlanDetail, onEvent: (WarehousePlanEvent) -> Unit) {
    when {
        plan.canLoad -> PrimaryButton(
            stringResource(Res.string.loading_wh_loaded),
            onClick = { onEvent(WarehousePlanEvent.Ask(WarehouseAsk.Load)) },
            enabled = !state.busy && !state.editing && plan.products.isNotEmpty(),
            modifier = Modifier.padding(16.dp),
        )
        plan.canGate -> PrimaryButton(
            stringResource(Res.string.loading_wh_gate),
            onClick = { onEvent(WarehousePlanEvent.Ask(WarehouseAsk.Gate)) },
            enabled = !state.busy && !state.editing,
            modifier = Modifier.padding(16.dp),
        )
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
            Text(
                stringResource(
                    Res.string.loading_wh_vehicle,
                    plan.vehiclePlate?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.loading_wh_no_vehicle),
                    plan.driverName?.takeIf { it.isNotBlank() } ?: stringResource(Res.string.loading_wh_no_driver),
                ),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

// One product to pick: how much goes on, for how many shops, and the batches set aside for it.
@Composable
private fun ProductCard(product: PlanProduct, planned: Boolean, canChange: Boolean, onChange: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(productLabel(product), color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
            Text(
                if (planned) {
                    stringResource(Res.string.loading_wh_load_amount, cartonsText(product.cartons, product.loosePieces))
                } else {
                    stringResource(Res.string.loading_wh_loaded_amount, cartonsText(product.loadedCartons.cartons, product.loadedCartons.loosePieces))
                },
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            val shops = product.items.map { it.customerId }.distinct().size
            Text(
                if (shops == 1) stringResource(Res.string.loading_wh_for_one_shop) else stringResource(Res.string.loading_wh_for_shops, shops),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Batches(product, planned)
            if (canChange) SecondaryButton(stringResource(Res.string.loading_wh_change), onClick = onChange)
        }
    }
}

// The batches to pick, oldest expiry first. While planned, says so when the batches do not cover the whole amount.
@Composable
private fun Batches(product: PlanProduct, planned: Boolean) {
    val scheme = MaterialTheme.colorScheme
    val setAside = product.batches.sumOf { it.pieces }
    val wanted = if (planned) product.pieces else product.loaded
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (product.batches.isNotEmpty()) {
            SectionLabel(stringResource(if (planned) Res.string.loading_wh_batches_to_pick else Res.string.loading_wh_batches_loaded))
            product.batches.forEach { batch ->
                Text(
                    stringResource(
                        Res.string.loading_wh_batch,
                        batch.batchNo,
                        formatIsoDate(batch.expiryDate),
                        cartonsText(batch.cartons, batch.loosePieces),
                    ),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        if (planned && setAside < wanted) {
            Say(
                if (setAside == 0) {
                    stringResource(Res.string.loading_wh_none_set_aside)
                } else {
                    stringResource(Res.string.loading_wh_short, splitText(setAside, product.piecesPerCarton))
                },
            )
        }
    }
}

// What the shelves could not cover, from the reply to Loaded.
@Composable
private fun NotLoadedCard(rows: List<NotLoaded>) {
    if (rows.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(stringResource(Res.string.loading_wh_not_loaded))
        rows.forEach { row ->
            Say(
                stringResource(Res.string.loading_wh_not_loaded_row, row.product, cartonsText(row.cartons, row.loosePieces)),
                error = true,
            )
        }
    }
}

@Composable
private fun DocumentNumbers(plan: PlanDetail) {
    val scheme = MaterialTheme.colorScheme
    val note = plan.deliveryNoteNo?.takeIf { it.isNotBlank() }
    val pass = plan.gatePassNo?.takeIf { it.isNotBlank() }
    if (note == null && pass == null) return
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            note?.let {
                Text(stringResource(Res.string.loading_wh_delivery_note, it), color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            }
            pass?.let {
                Text(stringResource(Res.string.loading_wh_gate_pass, it), color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

// Changing one product's total: the new cartons and loose pieces, and why it changed.
@Composable
private fun ChangeDialog(edit: ProductEdit, busy: Boolean, onEvent: (WarehousePlanEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = { onEvent(WarehousePlanEvent.CloseEdit) },
        containerColor = scheme.surface,
        title = { Text(stringResource(Res.string.loading_wh_change)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(productLabel(edit.product), color = scheme.onSurface, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(Res.string.loading_wh_planned_now, cartonsText(edit.product.cartons, edit.product.loosePieces)),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    NumberField(
                        label = stringResource(Res.string.loading_wh_cartons),
                        value = edit.cartons,
                        onValueChange = { onEvent(WarehousePlanEvent.EditCartons(it)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                    NumberField(
                        label = stringResource(Res.string.loading_wh_pieces),
                        value = edit.pieces,
                        onValueChange = { onEvent(WarehousePlanEvent.EditPieces(it)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
                Field(
                    label = stringResource(Res.string.loading_wh_reason),
                    value = edit.reason,
                    onValueChange = { onEvent(WarehousePlanEvent.EditReason(it)) },
                    enabled = !busy,
                )
                edit.error?.let { Say(it, error = true) }
            }
        },
        confirmButton = {
            TextButton(onClick = { onEvent(WarehousePlanEvent.SaveEdit) }, enabled = !busy, modifier = Modifier.padding(horizontal = 4.dp)) {
                Text(stringResource(Res.string.loading_wh_save_change), style = MaterialTheme.typography.labelLarge, color = scheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = { onEvent(WarehousePlanEvent.CloseEdit) }, enabled = !busy) {
                Text(stringResource(Res.string.cancel), style = MaterialTheme.typography.labelLarge)
            }
        },
    )
}

private val previewStoreman = User(5, "Peter Phiri", "0991000005", "warehouse")

private val previewMilk = PlanProduct(
    productId = 1,
    code = "LV-MILK",
    brand = "Lucky",
    name = "Milk",
    packSize = "500 ml",
    companyCode = "LV",
    piecesPerCarton = 12,
    pieces = 48,
    cartons = 4,
    loosePieces = 0,
    items = listOf(
        PlanItem(itemId = 1, orderNumber = "SO-000012", customerId = 1, customerName = "Area 47 Superstore", pieces = 24, cartons = 2),
        PlanItem(itemId = 2, orderNumber = "SO-000014", customerId = 2, customerName = "Kanengo Wholesalers", pieces = 24, cartons = 2),
    ),
    batches = listOf(
        PlanBatch(batchId = 1, batchNo = "M-1", expiryDate = "2027-01-31", pieces = 24, cartons = 2),
        PlanBatch(batchId = 2, batchNo = "M-2", expiryDate = "2027-03-31", pieces = 12, cartons = 1),
    ),
)

private val previewTea = PlanProduct(
    productId = 2,
    code = "ZT-TEA",
    brand = "Zoona",
    name = "Tea",
    packSize = "sachet",
    companyCode = "ZT",
    piecesPerCarton = 40,
    pieces = 20,
    cartons = 0,
    loosePieces = 20,
    items = listOf(PlanItem(itemId = 3, orderNumber = "SO-000015", customerId = 1, customerName = "Area 47 Superstore", pieces = 20, loosePieces = 20)),
)

private val previewPlan = PlanDetail(
    id = 4,
    number = "LP-000004",
    facilityCode = "F1",
    facilityName = "Facility 1",
    status = "planned",
    sent = true,
    vehiclePlate = "LL 4521",
    driverName = "Kondwani Banda",
    products = listOf(previewMilk, previewTea),
    changes = listOf(
        PlanChange(
            id = 1, byWarehouse = false, by = "Mr. Abbas", at = "2026-10-23T09:00:00.000Z", productId = 1,
            product = "Lucky Milk 500 ml", piecesPerCarton = 12, orderNumber = "SO-000012",
            customerName = "Area 47 Superstore", before = 24, after = 48,
        ),
    ),
    canWarehouseChange = true,
    canLoad = true,
)

@Preview
@Composable
private fun WarehousePlanPreview() {
    SalesTheme {
        WarehousePlanScreen(
            state = WarehousePlanState(loading = false, plan = previewPlan, user = previewStoreman),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun WarehousePlanLoadedPreview() {
    SalesTheme {
        WarehousePlanScreen(
            state = WarehousePlanState(
                loading = false,
                user = previewStoreman,
                plan = previewPlan.copy(
                    status = "loaded",
                    deliveryNoteNo = "DN-000004",
                    gatePassNo = "GP-000004",
                    canWarehouseChange = false,
                    canLoad = false,
                    canGate = true,
                    changes = emptyList(),
                    products = listOf(
                        previewMilk.copy(loaded = 48, loadedCartons = PieceSplit(pieces = 48, cartons = 4)),
                        previewTea.copy(loaded = 20, loadedCartons = PieceSplit(pieces = 20, loosePieces = 20)),
                    ),
                ),
                notLoaded = listOf(NotLoaded(product = "Zoona Tea sachet", pieces = 20, cartons = 0, loosePieces = 20)),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun WarehousePlanChangePreview() {
    SalesTheme {
        WarehousePlanScreen(
            state = WarehousePlanState(
                loading = false,
                plan = previewPlan,
                user = previewStoreman,
                edit = ProductEdit(previewMilk, cartons = 3, pieces = 0, reason = ""),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
