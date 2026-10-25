package com.salesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.salesapp.resources.user_add_manager_for
import com.salesapp.resources.user_add_name
import com.salesapp.resources.user_add_no_manager_for
import com.salesapp.resources.user_edit_no_access
import com.salesapp.resources.user_edit_role_locked
import com.salesapp.resources.user_edit_save
import com.salesapp.resources.user_edit_saving
import com.salesapp.resources.user_edit_title
import com.salesapp.resources.users_label_companies
import com.salesapp.resources.users_label_phone
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun EditUserPage(
    id: Int,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: EditUserViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                EditUserEffect.Done -> onDone()
            }
        }
    }
    EditUserScreen(state, viewModel::onEvent, onBack)
}

// Changing someone: the same fields as adding them, without the PIN. A sales manager may change
// their own salesmen only, and never what job they do.
@Composable
fun EditUserScreen(state: EditUserState, onEvent: (EditUserEvent) -> Unit, onBack: () -> Unit) {
    val busy = state.busy
    val role = state.role
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.user_edit_title, state.name), onBack = onBack)
        when {
            state.loading -> LoadingState()
            state.loadError != null -> ErrorState(state.loadError, onRetry = { onEvent(EditUserEvent.Retry) })
            !state.allowed -> Say(stringResource(Res.string.user_edit_no_access), error = true, modifier = Modifier.padding(16.dp))
            else -> {
                Column(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    state.error?.let { Say(it, error = true) }
                    Field(stringResource(Res.string.user_add_name), state.name, { onEvent(EditUserEvent.Name(it)) }, enabled = !busy)
                    Field(
                        stringResource(Res.string.users_label_phone),
                        state.phone,
                        { onEvent(EditUserEvent.Phone(it)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        enabled = !busy,
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SectionLabel(stringResource(Res.string.user_add_job))
                        if (!state.canChangeRole) {
                            // A sales manager sees the job but cannot change it.
                            ChoiceButton(role?.label.orEmpty(), selected = true, onClick = {}, enabled = false, modifier = Modifier.fillMaxWidth())
                            Text(
                                stringResource(Res.string.user_edit_role_locked),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            ChoiceColumn {
                                Role.entries.forEach { option ->
                                    ChoiceButton(
                                        option.label,
                                        selected = role == option,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { onEvent(EditUserEvent.RoleChosen(option)) },
                                    )
                                }
                            }
                        }
                    }
                    if (role?.seesAllCompanies == true) {
                        Say(stringResource(Res.string.user_add_all_companies))
                    } else if (role != null) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SectionLabel(stringResource(Res.string.users_label_companies))
                            ChoiceColumn {
                                state.pickableCompanies.forEach { code ->
                                    ChoiceButton(
                                        companyName(code),
                                        selected = code in state.companies,
                                        enabled = !busy,
                                        modifier = Modifier.fillMaxWidth(),
                                        onClick = { onEvent(EditUserEvent.CompanyToggled(code)) },
                                    )
                                }
                            }
                        }
                    }
                    // A salesman works under a sales manager in each of their companies. A sales
                    // manager is that manager, so there is nothing for them to pick.
                    if (role == Role.Salesman && !state.isSalesManager) {
                        state.chosenCompanies.forEach { code ->
                            val managers = state.managersFor(code)
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                SectionLabel(stringResource(Res.string.user_add_manager_for, companyName(code)))
                                if (managers.isEmpty() && state.loading) {
                                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                                } else if (managers.isEmpty()) {
                                    Say(stringResource(Res.string.user_add_no_manager_for, companyName(code)))
                                } else {
                                    ChoiceColumn {
                                        managers.forEach { manager ->
                                            ChoiceButton(
                                                manager.name,
                                                selected = state.companyManagers[code] == manager.id,
                                                enabled = !busy,
                                                modifier = Modifier.fillMaxWidth(),
                                                onClick = { onEvent(EditUserEvent.ManagerChosen(code, manager.id)) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    PrimaryButton(
                        stringResource(if (state.busy) Res.string.user_edit_saving else Res.string.user_edit_save),
                        onClick = { onEvent(EditUserEvent.Save) },
                        enabled = !busy,
                    )
                }
            }
        }
    }
}

private val sampleUsers = listOf(
    UserRow(7, "A. Manager", "0881000001", "sales_manager", listOf("LV", "ZT")),
    UserRow(8, "B. Manager", "0881000002", "sales_manager", listOf("ZF", "GL")),
)

private fun sampleEdit(canChangeRole: Boolean = true) = EditUserState(
    loading = false,
    users = sampleUsers,
    name = "C. Seller",
    phone = "0881000003",
    role = Role.Salesman,
    companies = setOf("LV", "ZT"),
    myCompanies = listOf("LV", "ZT", "ZF", "GL"),
    companyManagers = mapOf("LV" to 7, "ZT" to 7),
    canChangeRole = canChangeRole,
)

@Preview
@Composable
private fun EditUserPreview() {
    SalesTheme { EditUserScreen(sampleEdit(), {}, {}) }
}

@Preview
@Composable
private fun EditUserByManagerPreview() {
    SalesTheme { EditUserScreen(sampleEdit(canChangeRole = false), {}, {}) }
}
