package com.salesapp.ui.loading

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.resources.Res
import com.salesapp.resources.loading_new_facility_companies
import com.salesapp.resources.loading_new_make
import com.salesapp.resources.loading_new_making
import com.salesapp.resources.loading_new_no_facility
import com.salesapp.resources.loading_new_title
import com.salesapp.resources.loading_new_which
import com.salesapp.resources.number_no_access
import com.salesapp.ui.catalog.facilityLabel
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun NewPlanPage(
    onBack: () -> Unit,
    onCreated: (Int) -> Unit,
    viewModel: NewPlanViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is NewPlanEffect.Created -> onCreated(effect.id)
            }
        }
    }
    NewPlanScreen(state, viewModel::onEvent, onBack)
}

// Makes a new loading plan. With two warehouses it asks which one first; with one it makes the plan straight away.
@Composable
fun NewPlanScreen(state: NewPlanState, onEvent: (NewPlanEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.loading_new_title), onBack = onBack)
        when {
            !state.allowed ->
                Say(stringResource(Res.string.number_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.loading -> LoadingState()
            state.noFacility ->
                Say(stringResource(Res.string.loading_new_no_facility), error = true, modifier = Modifier.padding(16.dp))
            state.choosing -> ChooseFacility(state, onEvent)
            // One warehouse, or the warehouses could not be fetched.
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(NewPlanEvent.Retry) })
            else -> Making()
        }
    }
}

@Composable
private fun Making() {
    Column(
        modifier = Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Text(
            stringResource(Res.string.loading_new_making),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.ChooseFacility(state: NewPlanState, onEvent: (NewPlanEvent) -> Unit) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SectionLabel(stringResource(Res.string.loading_new_which))
        ChoiceColumn {
            state.facilities.forEach { (code, companies) ->
                val label = if (code == "F1" || code == "F2") facilityLabel(code) else code
                ChoiceButton(
                    text = if (companies.isBlank()) label else stringResource(Res.string.loading_new_facility_companies, label, companies),
                    selected = state.chosen == code,
                    onClick = { onEvent(NewPlanEvent.FacilityChosen(code)) },
                    enabled = !state.creating,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        state.error?.let { Say(it, error = true) }
    }
    PrimaryButton(
        stringResource(if (state.creating) Res.string.loading_new_making else Res.string.loading_new_make),
        onClick = { onEvent(NewPlanEvent.Make) },
        enabled = state.chosen != null && !state.creating,
        modifier = Modifier.padding(16.dp),
    )
}

private val sampleFacilities = listOf(
    "F1" to "Lucky Ventures, Zoona Trading",
    "F2" to "Zangafee, Greenland",
)

@Preview
@Composable
private fun NewPlanChoosePreview() {
    SalesTheme {
        NewPlanScreen(
            state = NewPlanState(loading = false, facilities = sampleFacilities, chosen = "F1"),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun NewPlanMakingPreview() {
    SalesTheme {
        NewPlanScreen(
            state = NewPlanState(loading = false, facilities = sampleFacilities.take(1), chosen = "F1", creating = true),
            onEvent = {},
            onBack = {},
        )
    }
}
