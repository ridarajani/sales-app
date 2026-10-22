package com.salesapp.ui.stock

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.splitPieces
import com.salesapp.data.Product
import com.salesapp.data.ReceiptDetail
import com.salesapp.data.ReceiptLine
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.number_batch_line
import com.salesapp.resources.number_cartons
import com.salesapp.resources.number_company
import com.salesapp.resources.number_ctn_pcs
import com.salesapp.resources.number_done
import com.salesapp.resources.number_no_access
import com.salesapp.resources.number_pieces
import com.salesapp.resources.receive_add_product
import com.salesapp.resources.receive_add_to_list
import com.salesapp.resources.receive_batch
import com.salesapp.resources.receive_batch_hint
import com.salesapp.resources.receive_change
import com.salesapp.resources.receive_date
import com.salesapp.resources.receive_delivery_note
import com.salesapp.resources.receive_done_line
import com.salesapp.resources.receive_done_title
import com.salesapp.resources.receive_added_to
import com.salesapp.resources.receive_expiry
import com.salesapp.resources.receive_grn
import com.salesapp.resources.receive_help
import com.salesapp.resources.receive_no_products
import com.salesapp.resources.receive_per_carton
import com.salesapp.resources.receive_products
import com.salesapp.resources.receive_remove
import com.salesapp.resources.receive_save
import com.salesapp.resources.receive_search
import com.salesapp.resources.receive_supplier
import com.salesapp.resources.receive_title
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.DateField
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.NumberField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

internal fun formProductTitle(brand: String, name: String, packSize: String): String =
    listOf(brand, name, packSize).filter { it.isNotBlank() }.joinToString(" ")

@Composable
fun ReceiveStockPage(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ReceiveStockViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { onDone() }
    }
    ReceiveStockScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun ReceiveStockScreen(state: ReceiveStockState, onEvent: (ReceiveStockEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        when {
            !state.allowed -> {
                TopBar(stringResource(Res.string.receive_title), onBack = onBack)
                Say(stringResource(Res.string.number_no_access), modifier = Modifier.padding(16.dp))
            }
            state.receipt != null -> ReceiveDone(state, state.receipt, onEvent)
            state.editing != null -> ReceiveEditor(state, state.editing, onEvent)
            else -> ReceiveList(state, onEvent, onBack)
        }
    }
}

@Composable
private fun ColumnScope.ReceiveList(
    state: ReceiveStockState,
    onEvent: (ReceiveStockEvent) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    TopBar(stringResource(Res.string.receive_title), onBack = onBack)
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(stringResource(Res.string.receive_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(Res.string.number_company))
            ChoiceColumn {
                state.companies.forEach { code ->
                    ChoiceButton(
                        companyName(code),
                        selected = state.company == code,
                        onClick = { onEvent(ReceiveStockEvent.CompanyChosen(code)) },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = !state.busy,
                    )
                }
            }
        }
        Field(
            stringResource(Res.string.receive_supplier),
            state.supplier,
            { onEvent(ReceiveStockEvent.Supplier(it)) },
            enabled = !state.busy,
        )
        Field(
            stringResource(Res.string.receive_delivery_note),
            state.deliveryNote,
            { onEvent(ReceiveStockEvent.DeliveryNote(it)) },
            enabled = !state.busy,
        )
        DateField(stringResource(Res.string.receive_date), state.receivedOn, { onEvent(ReceiveStockEvent.ReceivedOn(it)) })
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(Res.string.receive_products))
            state.lines.forEachIndexed { index, line ->
                ListCard(onClick = null) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(
                            formProductTitle(line.product.brand, line.product.name, line.product.packSize),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            stringResource(Res.string.number_batch_line, line.batchNo, formatIsoDate(line.expiryDate)),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            stringResource(Res.string.number_ctn_pcs, line.cartons.toString(), line.pieces.toString()),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    SecondaryButton(
                        stringResource(Res.string.receive_remove),
                        onClick = { onEvent(ReceiveStockEvent.RemoveLine(index)) },
                        modifier = Modifier.padding(start = 8.dp).fillMaxWidth(0.4f),
                        enabled = !state.busy,
                    )
                }
            }
            SecondaryButton(
                stringResource(Res.string.receive_add_product),
                onClick = { onEvent(ReceiveStockEvent.OpenEditor) },
                enabled = state.company != null && !state.busy,
            )
        }
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
            PrimaryButton(
                stringResource(Res.string.receive_save),
                onClick = { onEvent(ReceiveStockEvent.Save) },
                enabled = state.company != null && state.lines.isNotEmpty(),
            )
        }
    }
}

@Composable
private fun ColumnScope.ReceiveEditor(
    state: ReceiveStockState,
    editing: EditingLine,
    onEvent: (ReceiveStockEvent) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    TopBar(stringResource(Res.string.receive_add_product), onBack = { onEvent(ReceiveStockEvent.CloseEditor) })
    val chosen = editing.product
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (chosen != null) {
            ListCard(onClick = null) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(formProductTitle(chosen.brand, chosen.name, chosen.packSize), style = MaterialTheme.typography.titleSmall)
                    Text(
                        stringResource(Res.string.receive_per_carton, chosen.piecesPerCarton.toString()),
                        color = scheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                SecondaryButton(
                    stringResource(Res.string.receive_change),
                    onClick = { onEvent(ReceiveStockEvent.ProductChosen(null)) },
                    modifier = Modifier.padding(start = 8.dp).fillMaxWidth(0.4f),
                )
            }
            Field(
                stringResource(Res.string.receive_batch),
                editing.batchNo,
                { onEvent(ReceiveStockEvent.BatchNo(it)) },
                placeholder = stringResource(Res.string.receive_batch_hint),
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
            )
            DateField(stringResource(Res.string.receive_expiry), editing.expiryDate, { onEvent(ReceiveStockEvent.Expiry(it)) })
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NumberField(
                    stringResource(Res.string.number_cartons),
                    editing.cartons,
                    { onEvent(ReceiveStockEvent.Cartons(it)) },
                    modifier = Modifier.weight(1f),
                )
                NumberField(
                    stringResource(Res.string.number_pieces),
                    editing.pieces,
                    { onEvent(ReceiveStockEvent.Pieces(it)) },
                    modifier = Modifier.weight(1f),
                )
            }
        } else {
            SearchField(
                editing.query,
                { onEvent(ReceiveStockEvent.Query(it)) },
                placeholder = stringResource(Res.string.receive_search),
            )
            when {
                state.productsLoading -> LoadingState()
                state.productsError != null -> ErrorState(state.productsError, onRetry = { onEvent(ReceiveStockEvent.RetryProducts) })
                else -> {
                    val q = editing.query.trim().lowercase()
                    val shown = state.products.filter {
                        q.isEmpty() || formProductTitle(it.brand, it.name, it.packSize).lowercase().contains(q) || it.code.lowercase().contains(q)
                    }
                    if (shown.isEmpty()) {
                        Text(stringResource(Res.string.receive_no_products), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        shown.forEach { product ->
                            ListCard(onClick = { onEvent(ReceiveStockEvent.ProductChosen(product)) }) {
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(formProductTitle(product.brand, product.name, product.packSize), style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        stringResource(Res.string.receive_per_carton, product.piecesPerCarton.toString()),
                                        color = scheme.onSurfaceVariant,
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (chosen != null) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            editing.error?.let { Say(it, error = true) }
            PrimaryButton(stringResource(Res.string.receive_add_to_list), onClick = { onEvent(ReceiveStockEvent.AddLine) })
        }
    } else if (editing.error != null) {
        Say(editing.error, error = true, modifier = Modifier.padding(16.dp))
    }
}

@Composable
private fun ColumnScope.ReceiveDone(
    state: ReceiveStockState,
    receipt: ReceiptDetail,
    onEvent: (ReceiveStockEvent) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    TopBar(stringResource(Res.string.receive_done_title))
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            stringResource(Res.string.receive_grn, receipt.number),
            style = MaterialTheme.typography.headlineSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            stringResource(Res.string.receive_added_to, receipt.lines.size.toString(), companyName(receipt.companyCode)),
            color = scheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        receipt.lines.forEach { line ->
            val (cartons, loose) = splitPieces(line.pieces, line.piecesPerCarton)
            ListCard(onClick = null) {
                Text(
                    stringResource(
                        Res.string.receive_done_line,
                        line.productName,
                        line.packSize,
                        line.batchNo,
                        cartons.toString(),
                        loose.toString(),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        PrimaryButton(stringResource(Res.string.number_done), onClick = { onEvent(ReceiveStockEvent.Finish) })
    }
}

private val previewProduct = Product(1, "KG025", "LV", "Kings", "Cooking oil", "2 L", 12)

@Preview
@Composable
private fun ReceiveStockPreview() {
    SalesTheme {
        ReceiveStockScreen(
            state = ReceiveStockState(
                companies = listOf("LV"),
                company = "LV",
                receivedOn = "2026-10-22",
                lines = listOf(ReceiveLine(previewProduct, "KG025-2610A", "2027-10-22", 10, 3)),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ReceiveStockEditorPreview() {
    SalesTheme {
        ReceiveStockScreen(
            state = ReceiveStockState(
                companies = listOf("LV"),
                company = "LV",
                products = listOf(previewProduct),
                editing = EditingLine(product = previewProduct, expiryDate = "2027-10-22", cartons = 5),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun ReceiveStockDonePreview() {
    SalesTheme {
        ReceiveStockScreen(
            state = ReceiveStockState(
                receipt = ReceiptDetail(
                    id = 1,
                    number = "LV-GRN-000001",
                    companyCode = "LV",
                    lines = listOf(ReceiptLine(1, "KG025", "Cooking oil", "2 L", "KG025-2610A", "2027-10-22", 123, 12)),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
