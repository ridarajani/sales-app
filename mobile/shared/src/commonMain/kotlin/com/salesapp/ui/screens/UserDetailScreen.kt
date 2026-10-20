package com.salesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import com.salesapp.data.Screen
import com.salesapp.data.companyName
import com.salesapp.data.roleLabel
import com.salesapp.ui.components.BigButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.StatusPill
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.Ink
import com.salesapp.ui.theme.Muted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Everything about one person, with buttons to reset their PIN or switch them off or on.
@Composable
fun UserDetailScreen(state: AppState, id: Int) {
    val me = state.user ?: return
    val row = state.userRows.firstOrNull { it.id == id }
    val scope = rememberCoroutineScope()
    var confirmOff by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun change(on: Boolean) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val updated = if (on) state.api.switchOn(id) else state.api.switchOff(id)
                state.userRows = state.userRows.map { if (it.id == id) updated else it }
                state.message = if (on) "${updated.name} switched on." else "${updated.name} switched off."
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
        TopBar(row?.name ?: "Person", onBack = { state.nav.pop() })
        if (row == null) {
            Say("This person is not in the list. Go back and open the list again.", modifier = Modifier.padding(16.dp))
            return
        }
        // Management only looks; a sales manager only looks after salesmen.
        val canManage = me.role == "admin" || (me.role == "sales_manager" && row.role == "salesman")
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            error?.let { Say(it, error = true) }
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                StatusPill(if (row.active) "Can sign in" else "Switched off", good = row.active)
            }
            Detail("Name", row.name)
            Detail("Job", roleLabel(row.role))
            Detail("Phone number", row.phone)
            Detail("Companies", row.companies.joinToString(", ") { companyName(it) })
            if (row.managerName != null) Detail("Works under", row.managerName)
            if (row.id == me.id) {
                Say("This is you.")
            } else if (canManage) {
                BigButton("Reset PIN", enabled = !busy, onClick = { state.nav.push(Screen.ResetPin(row.id)) })
                if (row.active) {
                    SecondaryButton("Switch off", danger = true, enabled = !busy, onClick = { confirmOff = true })
                } else {
                    SecondaryButton("Switch on", enabled = !busy, onClick = { change(true) })
                }
            }
        }
        if (confirmOff) {
            AlertDialog(
                onDismissRequest = { confirmOff = false },
                title = { Text("Switch off ${row.name}?") },
                text = { Text("They can't sign in until you switch them on.") },
                confirmButton = {
                    TextButton(onClick = {
                        confirmOff = false
                        change(false)
                    }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Switch off", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                },
                dismissButton = { TextButton(onClick = { confirmOff = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", fontSize = 16.sp) } },
            )
        }
    }
}

// A small grey label with the value under it.
@Composable
private fun Detail(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = Muted, fontSize = 14.sp)
        Text(value, color = Ink, fontSize = 17.sp, fontWeight = FontWeight.Bold)
    }
}
