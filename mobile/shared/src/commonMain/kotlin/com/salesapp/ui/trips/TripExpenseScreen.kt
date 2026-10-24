package com.salesapp.ui.trips

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.resources.Res
import com.salesapp.resources.sale_no_access
import com.salesapp.resources.trip_expense_amount
import com.salesapp.resources.trip_expense_from_float
import com.salesapp.resources.trip_expense_kind
import com.salesapp.resources.trip_expense_note
import com.salesapp.resources.trip_expense_photo
import com.salesapp.resources.trip_expense_save
import com.salesapp.resources.trip_expense_saving
import com.salesapp.resources.trip_expense_title
import com.salesapp.ui.cash.PhotoField
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun TripExpensePage(
    tripId: Int,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: TripExpenseViewModel = koinViewModel { parametersOf(tripId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                TripExpenseEffect.Saved -> onSaved()
            }
        }
    }
    TripExpenseScreen(state, viewModel::onEvent, onBack)
}

// Money spent on the trip, out of the float: what for, how much, and the bill.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TripExpenseScreen(state: TripExpenseState, onEvent: (TripExpenseEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.trip_expense_title), onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.sale_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(TripExpenseEvent.Retry) })
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionLabel(stringResource(Res.string.trip_expense_kind))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.kinds.forEach { kind ->
                            ChoiceButton(
                                text = kind,
                                selected = state.kind == kind,
                                onClick = { onEvent(TripExpenseEvent.KindChosen(kind)) },
                                enabled = !state.busy,
                            )
                        }
                    }
                    Field(
                        label = stringResource(Res.string.trip_expense_note),
                        value = state.note,
                        onValueChange = { onEvent(TripExpenseEvent.NoteChanged(it)) },
                        enabled = !state.busy,
                    )
                    MwkField(
                        label = stringResource(Res.string.trip_expense_amount),
                        amount = state.amount,
                        onAmountChange = { onEvent(TripExpenseEvent.AmountChanged(it)) },
                    )
                    PhotoField(
                        label = stringResource(Res.string.trip_expense_photo),
                        photo = state.photo,
                        cameraAvailable = state.cameraAvailable,
                        busy = state.busy,
                        onTake = { onEvent(TripExpenseEvent.TakePhoto) },
                        onRemove = { onEvent(TripExpenseEvent.RemovePhoto) },
                    )
                    Text(
                        stringResource(Res.string.trip_expense_from_float),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    state.actionError?.let { Say(it, error = true) }
                }
                PrimaryButton(
                    stringResource(if (state.busy) Res.string.trip_expense_saving else Res.string.trip_expense_save),
                    onClick = { onEvent(TripExpenseEvent.Save) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private val sampleKinds = listOf("Fuel", "Lodging", "Food allowance", "Loading labour", "Vehicle repair", "Other")

@Preview
@Composable
private fun TripExpensePreview() {
    SalesTheme {
        TripExpenseScreen(
            TripExpenseState(loading = false, kinds = sampleKinds, kind = "Fuel", amount = 30_000, cameraAvailable = true),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun TripExpenseOtherPreview() {
    SalesTheme {
        TripExpenseScreen(
            TripExpenseState(loading = false, kinds = sampleKinds, kind = "Other", note = "Two new tyres", amount = 85_000, cameraAvailable = true),
            onEvent = {},
            onBack = {},
        )
    }
}
