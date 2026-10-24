package com.salesapp.ui.delivery

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.PlanDrop
import com.salesapp.data.PlanDropLine
import com.salesapp.resources.Res
import com.salesapp.resources.delivery_back_note
import com.salesapp.resources.delivery_cartons
import com.salesapp.resources.delivery_confirm
import com.salesapp.resources.delivery_confirming
import com.salesapp.resources.delivery_nothing
import com.salesapp.resources.delivery_not_invoiced
import com.salesapp.resources.delivery_note_label
import com.salesapp.resources.delivery_on_vehicle
import com.salesapp.resources.delivery_photo
import com.salesapp.resources.delivery_photo_added
import com.salesapp.resources.delivery_photo_again
import com.salesapp.resources.delivery_photo_remove
import com.salesapp.resources.delivery_pieces
import com.salesapp.resources.delivery_reason
import com.salesapp.resources.delivery_reason_closed
import com.salesapp.resources.delivery_reason_damaged
import com.salesapp.resources.delivery_reason_refused
import com.salesapp.resources.delivery_reason_short
import com.salesapp.resources.delivery_received_by
import com.salesapp.resources.delivery_received_by_hint
import com.salesapp.resources.delivery_refused_all
import com.salesapp.resources.delivery_refused_all_on
import com.salesapp.resources.delivery_refused_undo
import com.salesapp.resources.delivery_signature
import com.salesapp.resources.delivery_signature_clear
import com.salesapp.resources.delivery_signature_hint
import com.salesapp.resources.delivery_title
import com.salesapp.resources.delivery_too_much
import com.salesapp.resources.delivery_what
import com.salesapp.resources.number_no_access
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
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
fun ConfirmDeliveryPage(
    planId: Int,
    customerId: Int,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ConfirmDeliveryViewModel = koinViewModel { parametersOf(planId, customerId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ConfirmDeliveryEffect.Done -> onDone()
            }
        }
    }
    ConfirmDeliveryScreen(state, viewModel::onEvent, onBack)
}

// What the customer took, who received it and their signature. Anything they did not take goes
// back to the warehouse and stays open on their order.
@Composable
fun ConfirmDeliveryScreen(state: ConfirmDeliveryState, onEvent: (ConfirmDeliveryEvent) -> Unit, onBack: () -> Unit) {
    val drop = state.drop
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.delivery_title), onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.number_no_access), error = true, modifier = Modifier.padding(16.dp))
            drop != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Shop(drop)
                    when {
                        // The driver cannot hand over before the sales team has invoiced the drop.
                        state.waitingForInvoices -> Say(stringResource(Res.string.delivery_not_invoiced), error = true)
                        state.nothingToDeliver -> Say(stringResource(Res.string.delivery_nothing))
                        else -> Form(state, onEvent)
                    }
                }
                if (!state.waitingForInvoices && !state.nothingToDeliver) {
                    PrimaryButton(
                        stringResource(if (state.busy) Res.string.delivery_confirming else Res.string.delivery_confirm),
                        onClick = { onEvent(ConfirmDeliveryEvent.Confirm) },
                        enabled = !state.busy,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(ConfirmDeliveryEvent.Retry) })
            else -> LoadingState()
        }
    }
}

@Composable
private fun Shop(drop: PlanDrop) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(drop.customerName, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
            if (drop.area.isNotBlank()) {
                Text(drop.area, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            if (drop.orders.isNotEmpty()) {
                Text(drop.orders.joinToString(", "), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
        }
    }
}

@Composable
private fun Form(state: ConfirmDeliveryState, onEvent: (ConfirmDeliveryEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    SectionLabel(stringResource(Res.string.delivery_what))
    if (state.refusedAll) Say(stringResource(Res.string.delivery_refused_all_on))
    state.lines.forEach { line -> LineCard(line, state.busy, onEvent) }
    Text(
        stringResource(Res.string.delivery_back_note),
        color = scheme.onSurfaceVariant,
        style = MaterialTheme.typography.bodySmall,
    )
    Field(
        label = stringResource(Res.string.delivery_received_by),
        value = state.receivedBy,
        onValueChange = { onEvent(ConfirmDeliveryEvent.ReceivedByChanged(it)) },
        placeholder = stringResource(Res.string.delivery_received_by_hint),
        enabled = !state.busy,
    )
    // A drop that was refused completely needs a reason but no signature.
    if (state.needsSignature) {
        SectionLabel(stringResource(Res.string.delivery_signature))
        SignaturePad(
            strokes = state.strokes,
            onStrokeDone = { onEvent(ConfirmDeliveryEvent.StrokeDone(it)) },
            onSize = { onEvent(ConfirmDeliveryEvent.PadSized(it)) },
            hint = stringResource(Res.string.delivery_signature_hint),
            enabled = !state.busy,
        )
        SecondaryButton(
            stringResource(Res.string.delivery_signature_clear),
            onClick = { onEvent(ConfirmDeliveryEvent.ClearSignature) },
            enabled = !state.busy && state.hasSignature,
        )
    }
    if (state.cameraAvailable) {
        if (state.photo == null) {
            SecondaryButton(
                stringResource(Res.string.delivery_photo),
                onClick = { onEvent(ConfirmDeliveryEvent.TakePhoto) },
                enabled = !state.busy,
            )
        } else {
            Text(stringResource(Res.string.delivery_photo_added), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton(
                    stringResource(Res.string.delivery_photo_again),
                    onClick = { onEvent(ConfirmDeliveryEvent.TakePhoto) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
                SecondaryButton(
                    stringResource(Res.string.delivery_photo_remove),
                    onClick = { onEvent(ConfirmDeliveryEvent.RemovePhoto) },
                    enabled = !state.busy,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
    Field(
        label = stringResource(Res.string.delivery_note_label),
        value = state.note,
        onValueChange = { onEvent(ConfirmDeliveryEvent.NoteChanged(it)) },
        enabled = !state.busy,
    )
    SecondaryButton(
        stringResource(if (state.refusedAll) Res.string.delivery_refused_undo else Res.string.delivery_refused_all),
        onClick = { onEvent(if (state.refusedAll) ConfirmDeliveryEvent.UndoRefuseAll else ConfirmDeliveryEvent.RefuseAll) },
        enabled = !state.busy,
    )
    state.actionError?.let { Say(it, error = true) }
}

// One product: what went on the vehicle, what the customer took, and why less when it is less.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineCard(line: DeliveryLine, busy: Boolean, onEvent: (ConfirmDeliveryEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val itemId = line.line.itemId
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                listOf(line.line.brand, line.line.name, line.line.packSize).filter { it.isNotBlank() }.joinToString(" "),
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanyDot(line.line.companyCode)
            }
            Text(
                stringResource(
                    Res.string.delivery_on_vehicle,
                    line.line.orderNumber,
                    splitText(line.line.loaded, line.piecesPerCarton),
                ),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    label = stringResource(Res.string.delivery_cartons),
                    value = line.cartons,
                    onValueChange = { onEvent(ConfirmDeliveryEvent.EditCartons(itemId, it)) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                if (line.piecesPerCarton > 1) {
                    NumberField(
                        label = stringResource(Res.string.delivery_pieces),
                        value = line.pieces,
                        onValueChange = { onEvent(ConfirmDeliveryEvent.EditPieces(itemId, it)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (line.tooMuch) {
                Say(stringResource(Res.string.delivery_too_much, splitText(line.line.loaded, line.piecesPerCarton)), error = true)
            }
            // Taking less than was loaded always needs a reason.
            if (line.short) {
                Field(
                    label = stringResource(Res.string.delivery_reason),
                    value = line.reason,
                    onValueChange = { onEvent(ConfirmDeliveryEvent.EditReason(itemId, it)) },
                    enabled = !busy,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        stringResource(Res.string.delivery_reason_refused),
                        stringResource(Res.string.delivery_reason_damaged),
                        stringResource(Res.string.delivery_reason_short),
                        stringResource(Res.string.delivery_reason_closed),
                    ).forEach { quick ->
                        ChoiceButton(
                            text = quick,
                            selected = line.reason == quick,
                            onClick = { onEvent(ConfirmDeliveryEvent.EditReason(itemId, quick)) },
                            enabled = !busy,
                        )
                    }
                }
            }
        }
    }
}

private val sampleLines = listOf(
    PlanDropLine(
        orderNumber = "SO-000012", companyCode = "LV", lpoNo = "LPO-5521", productId = 1, code = "LV-MILK",
        brand = "Lucky", name = "Milk", packSize = "500 ml", pieces = 48, cartons = 4, loosePieces = 0,
        itemId = 11, loaded = 48,
    ),
    PlanDropLine(
        orderNumber = "SO-000015", companyCode = "ZT", productId = 2, code = "ZT-TEA",
        brand = "Zoona", name = "Tea", packSize = "sachet", pieces = 20, cartons = 0, loosePieces = 20,
        itemId = 13, loaded = 20,
    ),
)

private val sampleDrop = PlanDrop(
    customerId = 1,
    customerName = "Area 47 Superstore",
    area = "Area 47",
    orders = listOf("SO-000012", "SO-000015"),
    lines = sampleLines,
)

private fun sampleState(
    refusedAll: Boolean = false,
    shortFirst: Boolean = false,
    invoiced: Boolean = true,
) = ConfirmDeliveryState(
    loading = false,
    drop = sampleDrop.copy(invoiced = invoiced),
    cameraAvailable = true,
    refusedAll = refusedAll,
    receivedBy = if (refusedAll) "" else "Grace Banda",
    lines = listOf(
        DeliveryLine(
            line = sampleLines[0],
            piecesPerCarton = 12,
            cartons = if (refusedAll) 0 else if (shortFirst) 3 else 4,
            pieces = 0,
            reason = if (shortFirst) "Damaged" else "",
        ),
        DeliveryLine(line = sampleLines[1], piecesPerCarton = 40, cartons = 0, pieces = if (refusedAll) 0 else 20),
    ),
)

@Preview
@Composable
private fun ConfirmDeliveryPreview() {
    SalesTheme { ConfirmDeliveryScreen(sampleState(), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun ConfirmDeliveryShortPreview() {
    SalesTheme { ConfirmDeliveryScreen(sampleState(shortFirst = true), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun ConfirmDeliveryRefusedPreview() {
    SalesTheme { ConfirmDeliveryScreen(sampleState(refusedAll = true), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun ConfirmDeliveryNotInvoicedPreview() {
    SalesTheme { ConfirmDeliveryScreen(sampleState(invoiced = false), onEvent = {}, onBack = {}) }
}
