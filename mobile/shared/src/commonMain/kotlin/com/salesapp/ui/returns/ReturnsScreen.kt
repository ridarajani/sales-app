package com.salesapp.ui.returns

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.ReturnDetail
import com.salesapp.data.ReturnLine
import com.salesapp.data.ReturnListItem
import com.salesapp.resources.Res
import com.salesapp.resources.invoice_return_after
import com.salesapp.resources.invoice_return_back
import com.salesapp.resources.invoice_return_booked
import com.salesapp.resources.invoice_return_delivery
import com.salesapp.resources.invoice_return_to_book
import com.salesapp.resources.invoice_return_waiting
import com.salesapp.resources.return_book_action
import com.salesapp.resources.return_booked_by
import com.salesapp.resources.return_condition_damaged
import com.salesapp.resources.return_condition_good
import com.salesapp.resources.return_delivery_note
import com.salesapp.resources.return_detail_title
import com.salesapp.resources.return_goods
import com.salesapp.resources.return_made_by
import com.salesapp.resources.return_receive
import com.salesapp.resources.return_receive_help
import com.salesapp.resources.return_received_by
import com.salesapp.resources.return_receiving
import com.salesapp.resources.return_subtotal
import com.salesapp.resources.return_to_book_by_sales
import com.salesapp.resources.return_total
import com.salesapp.resources.return_vat
import com.salesapp.resources.returns_done
import com.salesapp.resources.returns_empty
import com.salesapp.resources.returns_for_invoice
import com.salesapp.resources.returns_from_invoice
import com.salesapp.resources.returns_help
import com.salesapp.resources.returns_no_access
import com.salesapp.resources.returns_no_match
import com.salesapp.resources.returns_search
import com.salesapp.resources.returns_title
import com.salesapp.resources.returns_to_book
import com.salesapp.resources.returns_waiting
import com.salesapp.resources.cancel
import com.salesapp.ui.cash.MoneyRow
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.FilterChips
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ReturnsPage(
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    viewModel: ReturnsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(ReturnsEvent.Refresh)
        onPauseOrDispose {}
    }
    ReturnsScreen(state, viewModel::onEvent, onBack, onOpen)
}

// Goods not taken at delivery, or brought back later. Sales books each one; the warehouse ticks
// when the goods are back.
@Composable
fun ReturnsScreen(
    state: ReturnsState,
    onEvent: (ReturnsEvent) -> Unit,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.returns_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.returns_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        SearchField(
            value = state.query,
            onValueChange = { onEvent(ReturnsEvent.QueryChanged(it)) },
            placeholder = stringResource(Res.string.returns_search),
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        // "All" is the chip component's own first option, so only the other three are listed.
        FilterChips(
            options = listOf(
                "to_book" to stringResource(Res.string.returns_to_book),
                "waiting" to stringResource(Res.string.returns_waiting),
                "done" to stringResource(Res.string.returns_done),
            ),
            selected = state.status.takeIf { it != "all" },
            onSelect = { onEvent(ReturnsEvent.StatusChosen(it ?: "all")) },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.returns.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(ReturnsEvent.Retry) }) }
                state.returns.isEmpty() -> item {
                    EmptyState(stringResource(if (state.filtered) Res.string.returns_no_match else Res.string.returns_empty))
                }
                else -> {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                stringResource(Res.string.returns_help),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                stringResource(Res.string.returns_from_invoice),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                    }
                    items(state.returns, key = { it.id }) { row -> ReturnRow(row, onClick = { onOpen(row.id) }) }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ReturnRow(row: ReturnListItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    row.customerName,
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                // The warehouse gets no money at all from the server.
                row.total?.let {
                    Text(formatMwk(it.toLong()), color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CompanyDot(row.companyCode)
                Text(row.number, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            Text(
                stringResource(Res.string.returns_for_invoice, row.invoiceNumber),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.caption,
            )
            row.products?.takeIf { it.isNotBlank() }?.let {
                Text(it, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(
                "${if (row.notTakenAtDelivery) stringResource(Res.string.invoice_return_delivery) else stringResource(Res.string.invoice_return_after)} · ${row.reason}",
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Badge(
                    stringResource(if (row.booked) Res.string.invoice_return_booked else Res.string.invoice_return_to_book),
                    tone = if (row.booked) BadgeTone.On else BadgeTone.Off,
                )
                Badge(
                    stringResource(if (row.waitingForWarehouse) Res.string.invoice_return_waiting else Res.string.invoice_return_back),
                    tone = if (row.waitingForWarehouse) BadgeTone.Off else BadgeTone.On,
                )
            }
            Text(
                formatIsoDate(localIsoDateFromUtcTimestamp(row.createdAt)),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.caption,
            )
        }
    }
}

@Composable
fun ReturnDetailPage(
    id: Int,
    onBack: () -> Unit,
    viewModel: ReturnDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(ReturnDetailEvent.Refresh)
        onPauseOrDispose {}
    }
    ReturnDetailScreen(state, viewModel::onEvent, onBack)
}

// One return: what came back, whether sales has booked it, and whether the goods are in yet.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReturnDetailScreen(state: ReturnDetailState, onEvent: (ReturnDetailEvent) -> Unit, onBack: () -> Unit) {
    val saved = state.salesReturn
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(saved?.let { stringResource(Res.string.return_detail_title, it.number) } ?: "", onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.returns_no_access), error = true, modifier = Modifier.padding(16.dp))
            saved != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ListCard(onClick = null) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(saved.customerName, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                            CompanyDot(saved.companyCode)
                            Text(
                                stringResource(Res.string.returns_for_invoice, saved.invoiceNumber),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(
                                "${if (saved.notTakenAtDelivery) stringResource(Res.string.invoice_return_delivery) else stringResource(Res.string.invoice_return_after)} · ${saved.reason}",
                                color = scheme.onSurface,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            saved.deliveryNoteNo?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    stringResource(Res.string.return_delivery_note, it),
                                    color = scheme.onSurfaceVariant,
                                    style = MaterialTheme.typography.caption,
                                )
                            }
                            if (saved.note.isNotBlank()) {
                                Text(saved.note, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                            }
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Badge(
                                    stringResource(if (saved.booked) Res.string.invoice_return_booked else Res.string.return_to_book_by_sales),
                                    tone = if (saved.booked) BadgeTone.On else BadgeTone.Off,
                                )
                                Badge(
                                    stringResource(if (saved.waitingForWarehouse) Res.string.invoice_return_waiting else Res.string.invoice_return_back),
                                    tone = if (saved.waitingForWarehouse) BadgeTone.Off else BadgeTone.On,
                                )
                            }
                            saved.bookedBy?.takeIf { it.isNotBlank() }?.let {
                                Text(stringResource(Res.string.return_booked_by, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                            }
                            saved.receivedBy?.takeIf { it.isNotBlank() }?.let {
                                Text(stringResource(Res.string.return_received_by, it), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
                            }
                            Text(
                                stringResource(Res.string.return_made_by, saved.createdBy),
                                color = scheme.onSurfaceVariant,
                                style = MaterialTheme.typography.caption,
                            )
                        }
                    }
                    SectionLabel(stringResource(Res.string.return_goods))
                    saved.lines.forEach { line -> GoodsRow(line, state, onEvent) }
                    // Only shown when the server sent money at all, so the warehouse sees no empty rows.
                    if (saved.showsMoney) {
                        ListCard(onClick = null) {
                            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                saved.subtotal?.let { MoneyRow(stringResource(Res.string.return_subtotal), it) }
                                saved.vat?.let { MoneyRow(stringResource(Res.string.return_vat), it) }
                                saved.total?.let { MoneyRow(stringResource(Res.string.return_total), it, strong = true) }
                            }
                        }
                    }
                    if (state.receiving) {
                        Text(
                            stringResource(Res.string.return_receive_help),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.actionError?.let { Say(it, error = true) }
                }
                Actions(state, saved, onEvent)
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(ReturnDetailEvent.Retry) })
            else -> LoadingState()
        }
    }
}

// One product that came back; while receiving, the warehouse says whether it is good or damaged.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoodsRow(line: ReturnLine, state: ReturnDetailState, onEvent: (ReturnDetailEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    listOf(line.brand, line.name, line.packSize).filter { it.isNotBlank() }.joinToString(" "),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                Text(line.quantity, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            }
            line.amount?.let {
                Text(formatMwk(it.toLong()), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
            if (state.receiving) {
                val damaged = line.id in state.damaged
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ChoiceButton(
                        text = stringResource(Res.string.return_condition_good),
                        selected = !damaged,
                        onClick = { onEvent(ReturnDetailEvent.ConditionChosen(line.id, false)) },
                        enabled = !state.busy,
                    )
                    ChoiceButton(
                        text = stringResource(Res.string.return_condition_damaged),
                        selected = damaged,
                        onClick = { onEvent(ReturnDetailEvent.ConditionChosen(line.id, true)) },
                        enabled = !state.busy,
                    )
                }
            } else if (line.condition == "damaged") {
                Badge(stringResource(Res.string.return_condition_damaged), tone = BadgeTone.Off)
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Actions(
    state: ReturnDetailState,
    saved: ReturnDetail,
    onEvent: (ReturnDetailEvent) -> Unit,
) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (saved.canBook) {
            PrimaryButton(
                stringResource(Res.string.return_book_action),
                onClick = { onEvent(ReturnDetailEvent.Book) },
                enabled = !state.busy,
            )
        }
        if (saved.canReceive) {
            if (state.receiving) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SecondaryButton(
                        stringResource(Res.string.cancel),
                        onClick = { onEvent(ReturnDetailEvent.StopReceiving) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                    PrimaryButton(
                        stringResource(if (state.busy) Res.string.return_receiving else Res.string.return_receive),
                        onClick = { onEvent(ReturnDetailEvent.Receive) },
                        enabled = !state.busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                SecondaryButton(
                    stringResource(Res.string.return_receive),
                    onClick = { onEvent(ReturnDetailEvent.StartReceiving) },
                    enabled = !state.busy,
                )
            }
        }
    }
}

private val sampleReturns = listOf(
    ReturnListItem(
        id = 5, number = "LV-SR-000003", companyCode = "LV", customerName = "Area 47 Superstore", invoiceId = 3,
        invoiceNumber = "LV-INV-000014", kind = "after", reason = "Damaged goods", total = 105_750.0,
        products = "Lucky 500 ml", createdAt = "2026-10-24T10:00:00.000Z",
    ),
    ReturnListItem(
        id = 4, number = "ZT-SR-000002", companyCode = "ZT", customerName = "Kanengo Wholesalers", invoiceId = 2,
        invoiceNumber = "ZT-INV-000009", kind = "delivery", reason = "Shop closed", total = 24_000.0,
        booked = true, goods = "received", products = "Zoona sachet", createdAt = "2026-10-23T16:00:00.000Z",
    ),
)

private val sampleReturn = ReturnDetail(
    id = 5,
    number = "LV-SR-000003",
    companyCode = "LV",
    customerId = 1,
    customerName = "Area 47 Superstore",
    invoiceId = 3,
    invoiceNumber = "LV-INV-000014",
    kind = "after",
    reason = "Damaged goods",
    subtotal = 90_000.0,
    vat = 15_750.0,
    total = 105_750.0,
    createdBy = "Mr. Abbas",
    createdAt = "2026-10-24T10:00:00.000Z",
    lines = listOf(
        ReturnLine(id = 1, invoiceLineId = 11, productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", piecesPerCarton = 12, pieces = 12, quantity = "1 ctn", amount = 90_000.0),
    ),
    canBook = true,
)

@Preview
@Composable
private fun ReturnsPreview() {
    SalesTheme {
        ReturnsScreen(state = ReturnsState(loading = false, returns = sampleReturns), onEvent = {}, onBack = {}, onOpen = {})
    }
}

@Preview
@Composable
private fun ReturnsWarehousePreview() {
    SalesTheme {
        ReturnsScreen(
            // The warehouse gets no money from the server, so none is shown.
            state = ReturnsState(loading = false, showsMoney = false, status = "waiting", returns = sampleReturns.map { it.copy(total = null) }),
            onEvent = {},
            onBack = {},
            onOpen = {},
        )
    }
}

@Preview
@Composable
private fun ReturnDetailPreview() {
    SalesTheme {
        ReturnDetailScreen(state = ReturnDetailState(loading = false, salesReturn = sampleReturn), onEvent = {}, onBack = {})
    }
}

@Preview
@Composable
private fun ReturnReceivingPreview() {
    SalesTheme {
        ReturnDetailScreen(
            state = ReturnDetailState(
                loading = false,
                // What the warehouse sees: no money at all, and the Good or Damaged choice open.
                salesReturn = sampleReturn.copy(
                    subtotal = null, vat = null, total = null, booked = true, bookedBy = "Mr. Abbas",
                    canBook = false, canReceive = true,
                    lines = sampleReturn.lines.map { it.copy(amount = null) },
                ),
                receiving = true,
                damaged = setOf(1),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
