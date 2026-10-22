package com.salesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.Role
import com.salesapp.data.UserRow
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.user_add_all_companies
import com.salesapp.resources.user_add_job
import com.salesapp.resources.user_add_name
import com.salesapp.resources.user_add_no_manager
import com.salesapp.resources.user_add_pin
import com.salesapp.resources.user_add_pin_note
import com.salesapp.resources.user_add_submit
import com.salesapp.resources.users_add
import com.salesapp.resources.users_add_salesman
import com.salesapp.resources.users_label_companies
import com.salesapp.resources.users_label_phone
import com.salesapp.resources.users_label_works_under
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// A form to add one person: name, phone, job, who they work under, companies and a PIN.
@Composable
fun AddUserPage(
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: AddUserViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                AddUserEffect.Done -> onDone()
            }
        }
    }
    AddUserScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun AddUserScreen(state: AddUserState, onEvent: (AddUserEvent) -> Unit, onBack: () -> Unit) {
    val role = state.role
    val busy = state.busy
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            stringResource(if (state.isSalesManager) Res.string.users_add_salesman else Res.string.users_add),
            onBack = onBack,
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            state.error?.let { Say(it, error = true) }
            Field(stringResource(Res.string.user_add_name), state.name, { onEvent(AddUserEvent.Name(it)) }, enabled = !busy)
            Field(
                stringResource(Res.string.users_label_phone),
                state.phone,
                { onEvent(AddUserEvent.Phone(it)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                enabled = !busy,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel(stringResource(Res.string.user_add_job))
                if (state.isSalesManager) {
                    ChoiceButton(Role.Salesman.label, selected = true, onClick = {}, modifier = Modifier.fillMaxWidth())
                } else {
                    ChoiceColumn {
                        Role.entries.forEach { option ->
                            ChoiceButton(
                                option.label,
                                selected = role == option,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onEvent(AddUserEvent.RoleChosen(option)) },
                            )
                        }
                    }
                }
            }

            if (role == Role.Salesman && !state.isSalesManager) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(Res.string.users_label_works_under))
                    if (state.managers.isEmpty() && state.loadingList) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    } else if (state.managers.isEmpty()) {
                        Say(stringResource(Res.string.user_add_no_manager))
                    } else {
                        ChoiceColumn {
                            state.managers.forEach { manager ->
                                ChoiceButton(
                                    manager.name,
                                    selected = state.managerId == manager.id,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = { onEvent(AddUserEvent.ManagerChosen(manager.id)) },
                                )
                            }
                        }
                    }
                }
            }

            if (role?.seesAllCompanies == true) {
                Say(stringResource(Res.string.user_add_all_companies))
            } else if (role != null && (role != Role.Salesman || state.managerId != null)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(Res.string.users_label_companies))
                    ChoiceColumn {
                        state.pickableCompanies.forEach { code ->
                            ChoiceButton(
                                companyName(code),
                                selected = code in state.companies,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { onEvent(AddUserEvent.CompanyToggled(code)) },
                            )
                        }
                    }
                }
            }

            Field(
                stringResource(Res.string.user_add_pin),
                state.pin,
                { onEvent(AddUserEvent.Pin(it)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                enabled = !busy,
                note = stringResource(Res.string.user_add_pin_note),
            )
            PrimaryButton(stringResource(Res.string.user_add_submit), enabled = !busy, onClick = { onEvent(AddUserEvent.Submit) })
        }
    }
}

private val sampleManagers = listOf(
    UserRow(7, "Banda Phiri", "+265881234567", "sales_manager", listOf("LV", "ZT")),
    UserRow(8, "Thoko Banda", "+265999876543", "sales_manager", listOf("ZF")),
)

@Preview
@Composable
private fun AddUserPreview() {
    SalesTheme {
        AddUserScreen(
            AddUserState(
                users = sampleManagers,
                name = "Chisomo Phiri",
                phone = "+265991234567",
                role = Role.Salesman,
                managerId = 7,
                companies = setOf("LV"),
                loadingList = false,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
