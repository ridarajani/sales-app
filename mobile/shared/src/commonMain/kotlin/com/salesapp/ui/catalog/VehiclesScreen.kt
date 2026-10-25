package com.salesapp.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.Vehicle
import com.salesapp.resources.Res
import com.salesapp.resources.catalog_off
import com.salesapp.resources.vehicle_line
import com.salesapp.resources.vehicle_no_description
import com.salesapp.resources.vehicle_no_driver
import com.salesapp.resources.vehicle_dormant_help
import com.salesapp.resources.vehicle_in_use
import com.salesapp.resources.vehicle_usual_driver
import com.salesapp.resources.vehicles_add
import com.salesapp.resources.vehicles_empty
import com.salesapp.resources.vehicles_title
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.ToggleRow
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun VehiclesPage(
    onBack: () -> Unit,
    onOpenVehicle: (Int) -> Unit,
    onAddVehicle: () -> Unit,
    viewModel: VehiclesViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is VehiclesEffect.OpenVehicle -> onOpenVehicle(effect.id)
                VehiclesEffect.AddVehicle -> onAddVehicle()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(VehiclesEvent.Refresh)
        onPauseOrDispose {}
    }
    VehiclesScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun VehiclesScreen(state: VehiclesState, onEvent: (VehiclesEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.vehicles_title), onBack = onBack)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.vehicles.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(VehiclesEvent.Retry) }) }
                state.vehicles.isEmpty() -> item { EmptyState(stringResource(Res.string.vehicles_empty)) }
                else -> {
                    state.actionError?.let { message -> item { Say(message, error = true) } }
                    item {
                        Text(
                            stringResource(Res.string.vehicle_dormant_help),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    items(state.vehicles, key = { it.id }) { vehicle ->
                    VehicleRow(
                        vehicle,
                        canEdit = state.canEdit,
                        changing = state.changing == vehicle.id,
                        onClick = { onEvent(VehiclesEvent.OpenVehicle(vehicle.id)) },
                        onInUseChanged = { inUse -> onEvent(VehiclesEvent.InUseChanged(vehicle.id, inUse)) },
                    )
                    }
                }
            }
        }
        if (state.canEdit) {
            PrimaryButton(
                stringResource(Res.string.vehicles_add),
                onClick = { onEvent(VehiclesEvent.AddVehicle) },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun VehicleRow(
    vehicle: Vehicle,
    canEdit: Boolean,
    changing: Boolean,
    onClick: () -> Unit,
    onInUseChanged: (Boolean) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val driver = if (vehicle.defaultDriverName.isNullOrBlank()) {
        stringResource(Res.string.vehicle_no_driver)
    } else {
        stringResource(Res.string.vehicle_usual_driver, vehicle.defaultDriverName)
    }
    ListCard(onClick = if (canEdit) onClick else null) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(vehicle.plate, color = scheme.onSurface, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                vehicle.description.ifBlank { stringResource(Res.string.vehicle_no_description) },
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                if (vehicle.facilityCode == null) driver else stringResource(Res.string.vehicle_line, facilityLabel(vehicle.facilityCode), driver),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (!vehicle.active) {
            Badge(stringResource(Res.string.catalog_off), tone = BadgeTone.Off, modifier = Modifier.padding(start = 8.dp))
        }
    }
    // Everyone who picks vehicles marks the ones they do not use, for themselves only.
    if (vehicle.active) {
        ToggleRow(
            stringResource(Res.string.vehicle_in_use),
            checked = !vehicle.dormant,
            onCheckedChange = { if (!changing) onInUseChanged(it) },
            modifier = Modifier.padding(start = 14.dp, end = 14.dp),
        )
    }
}

private val sampleVehicles = listOf(
    Vehicle(1, "MZ 4421", "3 ton truck", "F1", 7, "Banda Phiri"),
    // One this user does not use, so it sits last with its switch off.
    Vehicle(3, "MZ 1007", "Pick-up", null, null, null, dormant = true),
    Vehicle(2, "LL 120", "", null, null, null, active = false),
)

@Preview
@Composable
private fun VehiclesPreview() {
    SalesTheme {
        VehiclesScreen(VehiclesState(loading = false, vehicles = sampleVehicles, canEdit = true), onEvent = {}, onBack = {})
    }
}
