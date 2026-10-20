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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import com.salesapp.data.NewUserRequest
import com.salesapp.data.Role
import com.salesapp.data.companyCodes
import com.salesapp.data.companyName
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.PIN_LENGTH
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.Burgundy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// A form to add one person: name, phone, job, who they work under, companies and a PIN.
@Composable
fun AddUserScreen(state: AppState) {
    val me = state.user ?: return
    val isSalesManager = me.role == "sales_manager"
    val scope = rememberCoroutineScope()

    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var role by remember { mutableStateOf(if (isSalesManager) Role.Salesman else null) }
    var managerId by remember { mutableStateOf(if (isSalesManager) me.id else null) }
    var companies by remember { mutableStateOf(emptySet<String>()) }
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var loadingList by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    // The list of sales managers comes from the server.
    LaunchedEffect(Unit) {
        try {
            refreshUsers(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            error = e.message
        } finally {
            loadingList = false
        }
    }

    val managers = state.userRows.filter { it.role == "sales_manager" && it.active }
    val managerCompanies: List<String> = when {
        isSalesManager -> me.companies
        else -> managers.firstOrNull { it.id == managerId }?.companies ?: emptyList()
    }
    val chosenRole = role
    val allCompanies = chosenRole?.seesAllCompanies == true
    val pickableCompanies = if (chosenRole == Role.Salesman) {
        companyCodes.filter { it in managerCompanies }
    } else {
        companyCodes
    }

    fun submit() {
        if (busy) return
        val r = chosenRole
        error = when {
            name.isBlank() -> "Type the person's name."
            phone.isBlank() -> "Type the phone number."
            r == null -> "Choose what job the person does."
            r == Role.Salesman && managerId == null -> "Choose who the salesman works under."
            !r.seesAllCompanies && companies.isEmpty() -> "Choose at least one company."
            pin.length != PIN_LENGTH -> "The PIN must be $PIN_LENGTH numbers."
            else -> null
        }
        if (error != null || r == null) return
        busy = true
        scope.launch {
            try {
                val request = NewUserRequest(
                    name = name.trim(),
                    phone = phone.trim(),
                    role = r.code,
                    companies = if (r.seesAllCompanies) companyCodes else companyCodes.filter { it in companies },
                    managerId = if (r == Role.Salesman) managerId else null,
                    pin = pin,
                )
                val added = state.api.createUser(request)
                state.userRows = state.userRows + added
                state.message = "${added.name} added."
                state.nav.pop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.isSignedOut) state.signedOut("You were signed out. Sign in again.") else error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(if (isSalesManager) "Add a salesman" else "Add a user", onBack = { state.nav.pop() })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            error?.let { Say(it, error = true) }
            Field("Name", name, { name = it }, enabled = !busy)
            Field(
                "Phone number",
                phone,
                { phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                enabled = !busy,
            )

            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionLabel("Job")
                if (isSalesManager) {
                    ChoiceButton(Role.Salesman.label, selected = true, onClick = {}, modifier = Modifier.fillMaxWidth())
                } else {
                    ChoiceColumn {
                        Role.entries.forEach { option ->
                            ChoiceButton(
                                option.label,
                                selected = role == option,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = {
                                    role = option
                                    companies = emptySet()
                                    if (option != Role.Salesman) managerId = null
                                },
                            )
                        }
                    }
                }
            }

            if (chosenRole == Role.Salesman && !isSalesManager) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Works under")
                    if (managers.isEmpty() && loadingList) {
                        CircularProgressIndicator(color = Burgundy)
                    } else if (managers.isEmpty()) {
                        Say("There is no active sales manager yet. Add a sales manager first.")
                    } else {
                        ChoiceColumn {
                            managers.forEach { manager ->
                                ChoiceButton(
                                    manager.name,
                                    selected = managerId == manager.id,
                                    enabled = !busy,
                                    modifier = Modifier.fillMaxWidth(),
                                    onClick = {
                                        managerId = manager.id
                                        companies = emptySet()
                                    },
                                )
                            }
                        }
                    }
                }
            }

            if (allCompanies) {
                Say("Sees all four companies")
            } else if (chosenRole != null && (chosenRole != Role.Salesman || managerId != null)) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel("Companies")
                    ChoiceColumn {
                        pickableCompanies.forEach { code ->
                            ChoiceButton(
                                companyName(code),
                                selected = code in companies,
                                enabled = !busy,
                                modifier = Modifier.fillMaxWidth(),
                                onClick = { companies = if (code in companies) companies - code else companies + code },
                            )
                        }
                    }
                }
            }

            Field(
                "PIN",
                pin,
                { pin = it.filter { c -> c.isDigit() }.take(PIN_LENGTH) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                enabled = !busy,
                note = "The user types this to sign in. They can't change it; the admin resets it.",
            )
            PrimaryButton("Add user", enabled = !busy, onClick = ::submit)
        }
    }
}
