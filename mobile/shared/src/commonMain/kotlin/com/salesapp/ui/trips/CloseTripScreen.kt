package com.salesapp.ui.trips

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatMwk
import com.salesapp.data.TripLine
import com.salesapp.resources.Res
import com.salesapp.resources.close_action
import com.salesapp.resources.close_back
import com.salesapp.resources.close_cartons
import com.salesapp.resources.close_cash
import com.salesapp.resources.close_cash_expected
import com.salesapp.resources.close_cash_expenses
import com.salesapp.resources.close_cash_float
import com.salesapp.resources.close_cash_sales
import com.salesapp.resources.close_closing
import com.salesapp.resources.close_help
import com.salesapp.resources.close_matches
import com.salesapp.resources.close_missing
import com.salesapp.resources.close_note
import com.salesapp.resources.close_note_needed
import com.salesapp.resources.close_over
import com.salesapp.resources.close_pieces
import com.salesapp.resources.close_short
import com.salesapp.resources.close_should_come_back
import com.salesapp.resources.close_title_day
import com.salesapp.resources.close_title_trip
import com.salesapp.resources.close_too_much
import com.salesapp.resources.sale_no_access
import com.salesapp.resources.trip_close_blocked
import com.salesapp.ui.cash.MoneyRow
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
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
fun CloseTripPage(
    tripId: Int,
    onBack: () -> Unit,
    onClosed: () -> Unit,
    viewModel: CloseTripViewModel = koinViewModel { parametersOf(tripId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CloseTripEffect.Closed -> onClosed()
            }
        }
    }
    CloseTripScreen(state, viewModel::onEvent, onBack)
}

// The end of the day or the trip: count what came back and the cash, with the difference and
// anything missing worked out as it is typed.
@Composable
fun CloseTripScreen(state: CloseTripState, onEvent: (CloseTripEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            stringResource(if (state.trip?.isMinibus == true) Res.string.close_title_day else Res.string.close_title_trip),
            onBack = onBack,
        )
        when {
            !state.allowed -> Say(stringResource(Res.string.sale_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(CloseTripEvent.Retry) })
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    // Everything on the phone has to reach the server first: its stock and cash count.
                    if (state.blocked) Say(stringResource(Res.string.trip_close_blocked), error = true)
                    Text(stringResource(Res.string.close_help), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    SectionLabel(stringResource(Res.string.close_back))
                    state.lines.forEach { line -> LineCard(line, state.busy, onEvent) }
                    ListCard(onClick = null) {
                        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            MoneyRow(stringResource(Res.string.close_cash_sales), state.cashSales)
                            MoneyRow(stringResource(Res.string.close_cash_float), state.float)
                            if (state.cashExpenses > 0) {
                                MoneyRow(stringResource(Res.string.close_cash_expenses), state.cashExpenses, negative = true)
                            }
                            MoneyRow(stringResource(Res.string.close_cash_expected), state.expected.toDouble(), strong = true)
                        }
                    }
                    MwkField(
                        label = stringResource(Res.string.close_cash),
                        amount = state.counted,
                        onAmountChange = { onEvent(CloseTripEvent.CountedChanged(it)) },
                    )
                    Difference(state)
                    Field(
                        label = stringResource(Res.string.close_note),
                        value = state.note,
                        onValueChange = { onEvent(CloseTripEvent.NoteChanged(it)) },
                        enabled = !state.busy,
                    )
                    if (state.needsNote && state.note.isBlank()) {
                        Text(
                            stringResource(Res.string.close_note_needed),
                            color = scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    state.actionError?.let { Say(it, error = true) }
                }
                PrimaryButton(
                    stringResource(if (state.busy) Res.string.close_closing else Res.string.close_action),
                    onClick = { onEvent(CloseTripEvent.Close) },
                    enabled = !state.busy && !state.blocked,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

// The cash difference and anything missing, worked out as the leader types.
@Composable
private fun Difference(state: CloseTripState) {
    val difference = state.difference
    val missing = state.missingPieces
    if (difference == null && missing == 0) return
    val parts = buildList {
        when {
            difference == null -> Unit
            difference < 0L -> add(stringResource(Res.string.close_short, formatMwk(-difference)))
            difference > 0L -> add(stringResource(Res.string.close_over, formatMwk(difference)))
            else -> add(stringResource(Res.string.close_matches))
        }
    }
    if (parts.isEmpty()) return
    Say(parts.joinToString(" "), error = (difference ?: 0L) != 0L)
}

@Composable
private fun LineCard(line: CloseLine, busy: Boolean, onEvent: (CloseTripEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val productId = line.line.productId
    ListCard(onClick = null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(line.line.label, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(Res.string.close_should_come_back, splitText(line.should, line.perCarton)),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField(
                    label = stringResource(Res.string.close_cartons),
                    value = line.cartons,
                    onValueChange = { onEvent(CloseTripEvent.EditCartons(productId, it)) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                )
                if (line.perCarton > 1) {
                    NumberField(
                        label = stringResource(Res.string.close_pieces),
                        value = line.pieces,
                        onValueChange = { onEvent(CloseTripEvent.EditPieces(productId, it)) },
                        enabled = !busy,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            when {
                line.tooMuch -> Say(stringResource(Res.string.close_too_much, splitText(line.should, line.perCarton)), error = true)
                line.missing > 0 && line.back > 0 ->
                    Text(
                        stringResource(Res.string.close_missing, splitText(line.missing, line.perCarton)),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
            }
        }
    }
}

private val sampleCloseLines = listOf(
    TripLine(productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", piecesPerCarton = 12, loaded = 120, sold = 48, onVehicle = 72),
    TripLine(productId = 2, brand = "Zoona", name = "Tea", packSize = "sachet", piecesPerCarton = 40, loaded = 200, sold = 40, onVehicle = 160),
)

private fun sampleClose(counted: Long? = null, waiting: Int = 0, back: Int? = null) = CloseTripState(
    loading = false,
    trip = com.salesapp.data.TripDetail(
        id = 2, number = "TRP-000002", kind = "upcountry", route = "Salima and Nkhotakota", status = "out",
        float = 100_000.0, cashSales = 230_000.0, cashExpenses = 30_000.0, cashNow = 300_000.0,
        lines = sampleCloseLines, canClose = true,
    ),
    lines = sampleCloseLines.map { CloseLine(it, cartons = back) },
    counted = counted,
    waitingToSend = waiting,
)

@Preview
@Composable
private fun CloseTripPreview() {
    SalesTheme { CloseTripScreen(sampleClose(), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun CloseTripShortPreview() {
    SalesTheme { CloseTripScreen(sampleClose(counted = 299_000, back = 4), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun CloseTripMatchesPreview() {
    SalesTheme { CloseTripScreen(sampleClose(counted = 300_000, back = 6), onEvent = {}, onBack = {}) }
}

@Preview
@Composable
private fun CloseTripBlockedPreview() {
    SalesTheme { CloseTripScreen(sampleClose(waiting = 3), onEvent = {}, onBack = {}) }
}
