package com.salesapp.ui.returns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatMwk
import com.salesapp.data.InvoiceCustomer
import com.salesapp.data.InvoiceDetail
import com.salesapp.data.InvoiceLine
import com.salesapp.data.ReturnDetail
import com.salesapp.resources.Res
import com.salesapp.resources.return_cartons
import com.salesapp.resources.return_done
import com.salesapp.resources.return_help
import com.salesapp.resources.return_line_up_to
import com.salesapp.resources.return_not_delivered
import com.salesapp.resources.return_note
import com.salesapp.resources.return_pieces
import com.salesapp.resources.return_save
import com.salesapp.resources.return_saved
import com.salesapp.resources.return_saved_lower
import com.salesapp.resources.return_saving
import com.salesapp.resources.return_title
import com.salesapp.resources.return_too_much
import com.salesapp.resources.return_why
import com.salesapp.resources.returns_no_access
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.loading.splitText
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun NewReturnPage(
    invoiceId: Int,
    onBack: () -> Unit,
    viewModel: NewReturnViewModel = koinViewModel { parametersOf(invoiceId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NewReturnScreen(state, viewModel::onEvent, onBack)
}

// Goods the customer gave back after delivery: how much of each line came back, and why.
@Composable
fun NewReturnScreen(state: NewReturnState, onEvent: (NewReturnEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.return_title), onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.returns_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.saved != null -> Saved(state.saved, onBack)
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(NewReturnEvent.Retry) })
            // Nothing is delivered yet, so nothing can come back; a shortage makes its own return.
            state.notDeliveredYet || state.lines.isEmpty() ->
                Say(stringResource(Res.string.return_not_delivered), modifier = Modifier.padding(16.dp))
            else -> Form(state, onEvent)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Form(state: NewReturnState, onEvent: (NewReturnEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.invoice?.let { invoice ->
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(invoice.customer.name, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                    Text(invoice.number, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        Text(stringResource(Res.string.return_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        state.lines.forEach { entry -> LineCard(entry, state.busy, onEvent) }
        SectionLabel(stringResource(Res.string.return_why))
        ChoiceColumn {
            state.reasons.forEach { reason ->
                ChoiceButton(
                    text = reason,
                    selected = state.reason == reason,
                    onClick = { onEvent(NewReturnEvent.ReasonChosen(reason)) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Field(
            label = stringResource(Res.string.return_note),
            value = state.note,
            onValueChange = { onEvent(NewReturnEvent.NoteChanged(it)) },
            enabled = !state.busy,
        )
        state.actionError?.let { Say(it, error = true) }
    }
    PrimaryButton(
        stringResource(if (state.busy) Res.string.return_saving else Res.string.return_save),
        onClick = { onEvent(NewReturnEvent.Save) },
        enabled = !state.busy && state.anything,
        modifier = Modifier.padding(16.dp),
    )
}

// One line: what was invoiced, the most that can still come back, and what came back.
@Composable
private fun LineCard(entry: ReturnLineEntry, busy: Boolean, onEvent: (NewReturnEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val lineId = entry.line.lineId
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                listOf(entry.line.brand, entry.line.name, entry.line.packSize).filter { it.isNotBlank() }.joinToString(" "),
                color = scheme.onSurface,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(
                    Res.string.return_line_up_to,
                    entry.line.quantity,
                    splitText(entry.most, entry.perCarton),
                ),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    label = stringResource(Res.string.return_cartons),
                    value = entry.cartons,
                    onValueChange = { onEvent(NewReturnEvent.EditCartons(lineId, it)) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                if (entry.perCarton > 1) {
                    NumberField(
                        label = stringResource(Res.string.return_pieces),
                        value = entry.pieces,
                        onValueChange = { onEvent(NewReturnEvent.EditPieces(lineId, it)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            if (entry.tooMuch) {
                Say(stringResource(Res.string.return_too_much, splitText(entry.most, entry.perCarton)), error = true)
            }
        }
    }
}

// The return as it was booked, and how much lighter the invoice is.
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Saved(saved: ReturnDetail, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Say(stringResource(Res.string.return_saved, saved.number))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                saved.lines.forEach { line ->
                    Text(
                        "${listOf(line.brand, line.name, line.packSize).filter { it.isNotBlank() }.joinToString(" ")} · ${line.quantity}",
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        saved.total?.let {
            Text(
                stringResource(Res.string.return_saved_lower, formatMwk(it.toLong())),
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
            )
        }
    }
    PrimaryButton(stringResource(Res.string.return_done), onClick = onBack, modifier = Modifier.padding(16.dp))
}

private val sampleInvoice = InvoiceDetail(
    id = 3,
    number = "LV-INV-000014",
    companyCode = "LV",
    customer = InvoiceCustomer(id = 1, name = "Area 47 Superstore", area = "Area 47"),
    lines = listOf(
        InvoiceLine(
            lineId = 11, productId = 1, code = "LV-MILK", brand = "Lucky", name = "Milk", packSize = "500 ml",
            piecesPerCarton = 12, pieces = 48, quantity = "4 ctn", pricePerCarton = 90_000.0, amount = 360_000.0,
            delivered = 48, returned = 0, canReturn = 48,
        ),
        InvoiceLine(
            lineId = 12, productId = 2, code = "LV-OIL", brand = "Lucky", name = "Cooking oil", packSize = "2 L",
            piecesPerCarton = 6, pieces = 12, quantity = "2 ctn", pricePerCarton = 60_000.0, amount = 120_000.0,
            delivered = 12, returned = 6, canReturn = 6,
        ),
    ),
)

private fun sampleState(filled: Boolean = false) = NewReturnState(
    loading = false,
    invoice = sampleInvoice,
    lines = sampleInvoice.lines.map { line ->
        ReturnLineEntry(line, cartons = if (filled && line.lineId == 11) 1 else null, pieces = null)
    },
    reason = "Damaged goods",
)

@Preview
@Composable
private fun NewReturnPreview() {
    SalesTheme { NewReturnScreen(sampleState(filled = true), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun NewReturnNotDeliveredPreview() {
    SalesTheme {
        NewReturnScreen(
            NewReturnState(loading = false, invoice = sampleInvoice.copy(lines = sampleInvoice.lines.map { it.copy(canReturn = null, delivered = null) })),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun NewReturnSavedPreview() {
    SalesTheme {
        NewReturnScreen(
            sampleState().copy(
                saved = ReturnDetail(
                    id = 5,
                    number = "LV-SR-000003",
                    companyCode = "LV",
                    customerName = "Area 47 Superstore",
                    invoiceNumber = "LV-INV-000014",
                    reason = "Damaged goods",
                    total = 105_750.0,
                    booked = true,
                    lines = listOf(
                        com.salesapp.data.ReturnLine(
                            id = 1, invoiceLineId = 11, productId = 1, brand = "Lucky", name = "Milk",
                            packSize = "500 ml", piecesPerCarton = 12, pieces = 12, quantity = "1 ctn", amount = 90_000.0,
                        ),
                    ),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
