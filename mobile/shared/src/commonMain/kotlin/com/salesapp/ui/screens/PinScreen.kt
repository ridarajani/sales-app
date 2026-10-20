package com.salesapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import com.salesapp.ui.components.PIN_LENGTH
import com.salesapp.ui.components.PinEntry
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.Burgundy
import com.salesapp.ui.theme.Muted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

// Sign in, step two: greet the person by name and take the four-digit PIN.
@Composable
fun PinScreen(state: AppState, phone: String, name: String) {
    val scope = rememberCoroutineScope()
    var pin by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun tryLogin(enteredPin: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                val reply = state.api.login(phone, enteredPin)
                state.loginNotice = null
                state.signedIn(reply.token, reply.user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                error = e.message
                pin = ""
            } finally {
                busy = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("Hello, ${name.trim().substringBefore(' ')}", onBack = { state.nav.pop() }, sub = {
            Text("Type your PIN.", color = Muted, fontSize = 14.sp)
        })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            PinEntry(
                pin = pin,
                enabled = !busy,
                belowDots = {
                    // Always reserve room for the message so the keys never move.
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                        error?.let { Say(it, error = true) }
                    }
                },
                onChange = {
                    pin = it
                    if (it.length == PIN_LENGTH) tryLogin(it)
                },
            )
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                if (busy) CircularProgressIndicator(color = Burgundy)
            }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    "Not you? Go back and change the number.",
                    color = Muted,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !busy, role = Role.Button) { state.nav.pop() }
                        .padding(12.dp),
                )
            }
        }
    }
}
