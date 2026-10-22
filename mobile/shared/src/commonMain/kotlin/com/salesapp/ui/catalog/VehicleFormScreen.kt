package com.salesapp.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatPhone
import com.salesapp.core.normalisePhoneInput
import com.salesapp.data.Driver
import com.salesapp.resources.Res
import com.salesapp.resources.catalog_save
import com.salesapp.resources.vehicle_description
import com.salesapp.resources.vehicle_description_hint
import com.salesapp.resources.vehicle_driver
import com.salesapp.resources.vehicle_facility
import com.salesapp.resources.vehicle_in_use
import com.salesapp.resources.vehicle_in_use_note
import com.salesapp.resources.vehicle_no_driver
import com.salesapp.resources.vehicle_no_drivers
import com.salesapp.resources.vehicle_plate
import com.salesapp.resources.vehicle_plate_hint
import com.salesapp.resources.vehicles_add
import com.salesapp.resources.vehicles_edit
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.ToggleRow
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun VehicleFormPage(
    id: Int?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: VehicleFormViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { onDone() }
    }
    VehicleFormScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun VehicleFormScreen(state: VehicleFormState, onEvent: (VehicleFormEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopBar(stringResource(if (state.isEdit) Res.string.vehicles_edit else Res.string.vehicles_add), onBack = onBack)
        when {
            state.loading -> LoadingState()
            state.loadError != null -> ErrorState(state.loadError, onRetry = { onEvent(VehicleFormEvent.Retry) })
            else -> {
              Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                Field(
                    stringResource(Res.string.vehicle_plate),
                    state.plate,
                    { onEvent(VehicleFormEvent.Plate(it)) },
                    placeholder = stringResource(Res.string.vehicle_plate_hint),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Characters),
                    enabled = !state.busy,
                )
                Field(
                    stringResource(Res.string.vehicle_description),
                    state.description,
                    { onEvent(VehicleFormEvent.Description(it)) },
                    placeholder = stringResource(Res.string.vehicle_description_hint),
                    enabled = !state.busy,
                )
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(Res.string.vehicle_facility))
                    ChoiceColumn {
                        listOf<String?>("F1", "F2", null).forEach { code ->
                            ChoiceButton(
                                facilityLabel(code),
                                selected = state.facility == code,
                                onClick = { onEvent(VehicleFormEvent.Facility(code)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.busy,
                            )
                        }
                    }
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(Res.string.vehicle_driver))
                    ChoiceColumn {
                        ChoiceButton(
                            stringResource(Res.string.vehicle_no_driver),
                            selected = state.driverId == null,
                            onClick = { onEvent(VehicleFormEvent.DriverChosen(null)) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !state.busy,
                        )
                        state.drivers.forEach { driver ->
                            ChoiceButton(
                                listOf(driver.name, formatPhone(normalisePhoneInput(driver.phone))).filter { it.isNotBlank() }.joinToString(" · "),
                                selected = state.driverId == driver.id,
                                onClick = { onEvent(VehicleFormEvent.DriverChosen(driver.id)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.busy,
                            )
                        }
                    }
                    if (state.drivers.isEmpty()) {
                        Text(stringResource(Res.string.vehicle_no_drivers), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (state.isEdit) {
                    ToggleRow(
                        stringResource(Res.string.vehicle_in_use),
                        checked = state.active,
                        onCheckedChange = { onEvent(VehicleFormEvent.Active(it)) },
                        note = stringResource(Res.string.vehicle_in_use_note),
                    )
                }
              }
              Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                state.error?.let { Say(it, error = true) }
                PrimaryButton(
                    stringResource(Res.string.catalog_save),
                    onClick = { onEvent(VehicleFormEvent.Save) },
                    enabled = !state.busy,
                )
              }
            }
        }
    }
}

@Preview
@Composable
private fun VehicleFormPreview() {
    SalesTheme {
        VehicleFormScreen(
            state = VehicleFormState(
                isEdit = true,
                loading = false,
                drivers = listOf(Driver(7, "Banda Phiri", "0991234567"), Driver(8, "Chikondi Mwale", "0881234567")),
                plate = "MZ 4421",
                description = "3 ton truck",
                facility = "F1",
                driverId = 7,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
