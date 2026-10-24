package com.salesapp.ui.returns

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatMwk
import com.salesapp.data.InvoiceCustomer
import com.salesapp.data.InvoiceDetail
import com.salesapp.data.InvoiceNoteDetail
import com.salesapp.data.NOTE_KINDS
import com.salesapp.resources.Res
import com.salesapp.resources.invoice_note_owes_less
import com.salesapp.resources.invoice_note_owes_more
import com.salesapp.resources.note_amount
import com.salesapp.resources.note_credit_issued
import com.salesapp.resources.note_credit_issued_sub
import com.salesapp.resources.note_credit_received
import com.salesapp.resources.note_credit_received_sub
import com.salesapp.resources.note_debit_issued
import com.salesapp.resources.note_debit_issued_sub
import com.salesapp.resources.note_debit_received
import com.salesapp.resources.note_debit_received_sub
import com.salesapp.resources.note_extra
import com.salesapp.resources.note_help
import com.salesapp.resources.note_kind
import com.salesapp.resources.note_reason
import com.salesapp.resources.note_save
import com.salesapp.resources.note_saved
import com.salesapp.resources.note_saving
import com.salesapp.resources.note_their_number
import com.salesapp.resources.note_title
import com.salesapp.resources.return_done
import com.salesapp.resources.returns_no_access
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.sales
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun NewNotePage(
    invoiceId: Int,
    onBack: () -> Unit,
    viewModel: NewNoteViewModel = koinViewModel { parametersOf(invoiceId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    NewNoteScreen(state, viewModel::onEvent, onBack)
}

// A credit or debit note on an invoice, mostly for a price difference. Each kind says plainly
// whether the customer ends up owing more or less.
@Composable
fun NewNoteScreen(state: NewNoteState, onEvent: (NewNoteEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.note_title), onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.returns_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.saved != null -> Saved(state.saved, onBack)
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(NewNoteEvent.Retry) })
            else -> Form(state, onEvent)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Form(state: NewNoteState, onEvent: (NewNoteEvent) -> Unit) {
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
        Text(stringResource(Res.string.note_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
        SectionLabel(stringResource(Res.string.note_kind))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            NOTE_KINDS.forEach { kind -> KindChoice(kind, state, onEvent) }
        }
        MwkField(
            label = stringResource(Res.string.note_amount),
            amount = state.amount,
            onAmountChange = { onEvent(NewNoteEvent.AmountChanged(it)) },
        )
        SectionLabel(stringResource(Res.string.note_reason))
        ChoiceColumn {
            state.reasons.forEach { reason ->
                ChoiceButton(
                    text = reason,
                    selected = state.reason == reason,
                    onClick = { onEvent(NewNoteEvent.ReasonChosen(reason)) },
                    enabled = !state.busy,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        // Only a note the customer sent us has a number of their own.
        if (state.needsTheirNumber) {
            Field(
                label = stringResource(Res.string.note_their_number),
                value = state.theirNumber,
                onValueChange = { onEvent(NewNoteEvent.TheirNumberChanged(it)) },
                enabled = !state.busy,
            )
        }
        Field(
            label = stringResource(Res.string.note_extra),
            value = state.note,
            onValueChange = { onEvent(NewNoteEvent.NoteChanged(it)) },
            enabled = !state.busy,
        )
        state.actionError?.let { Say(it, error = true) }
    }
    PrimaryButton(
        stringResource(if (state.busy) Res.string.note_saving else Res.string.note_save),
        onClick = { onEvent(NewNoteEvent.Save) },
        enabled = !state.busy,
        modifier = Modifier.padding(16.dp),
    )
}

// A choice button with what it does to the customer's balance written under it.
@Composable
private fun KindChoice(kind: String, state: NewNoteState, onEvent: (NewNoteEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val sales = MaterialTheme.sales
    val chosen = state.kind == kind
    val shape = RoundedCornerShape(12.dp)
    val (label, effect) = when (kind) {
        "debit_issued" -> stringResource(Res.string.note_debit_issued) to stringResource(Res.string.note_debit_issued_sub)
        "debit_received" -> stringResource(Res.string.note_debit_received) to stringResource(Res.string.note_debit_received_sub)
        "credit_received" -> stringResource(Res.string.note_credit_received) to stringResource(Res.string.note_credit_received_sub)
        else -> stringResource(Res.string.note_credit_issued) to stringResource(Res.string.note_credit_issued_sub)
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .clip(shape)
            .background(if (chosen) sales.accentSoft else scheme.surface)
            .border(1.dp, if (chosen) scheme.primary else scheme.outline, shape)
            .clickable(enabled = !state.busy, role = Role.RadioButton) { onEvent(NewNoteEvent.KindChosen(kind)) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            label,
            color = if (chosen) scheme.primary else scheme.onSurface,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal,
        )
        Text(effect, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Saved(saved: InvoiceNoteDetail, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Say(stringResource(Res.string.note_saved, saved.number))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(saved.label, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall)
                Text(saved.reason, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                Text(
                    if (saved.owesChange >= 0) {
                        stringResource(Res.string.invoice_note_owes_more, formatMwk(saved.owesChange.toLong()))
                    } else {
                        stringResource(Res.string.invoice_note_owes_less, formatMwk(-saved.owesChange.toLong()))
                    },
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
    PrimaryButton(stringResource(Res.string.return_done), onClick = onBack, modifier = Modifier.padding(16.dp))
}

private val sampleInvoiceForNote = InvoiceDetail(
    id = 3,
    number = "LV-INV-000014",
    companyCode = "LV",
    customer = InvoiceCustomer(id = 1, name = "Area 47 Superstore"),
)

@Preview
@Composable
private fun NewNotePreview() {
    SalesTheme {
        NewNoteScreen(
            NewNoteState(loading = false, invoice = sampleInvoiceForNote, kind = "credit_issued", amount = 25_000),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun NewNoteReceivedPreview() {
    SalesTheme {
        NewNoteScreen(
            NewNoteState(
                loading = false,
                invoice = sampleInvoiceForNote,
                kind = "debit_received",
                amount = 15_000,
                theirNumber = "DN-2026-0042",
                reason = "Price difference",
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun NewNoteSavedPreview() {
    SalesTheme {
        NewNoteScreen(
            NewNoteState(
                loading = false,
                invoice = sampleInvoiceForNote,
                saved = InvoiceNoteDetail(
                    id = 2,
                    number = "LV-CN-000004",
                    companyCode = "LV",
                    customerName = "Area 47 Superstore",
                    invoiceNumber = "LV-INV-000014",
                    kind = "credit_issued",
                    amount = 25_000.0,
                    owesChange = -25_000.0,
                    reason = "Price difference",
                    label = "Credit note issued",
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
