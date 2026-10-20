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
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.core.AppConfig
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import com.salesapp.data.Screen
import com.salesapp.ui.components.BigButton
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.Say
import com.salesapp.ui.theme.Burgundy
import com.salesapp.ui.theme.Muted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private const val MIN_PHONE_DIGITS = 9

// Sign in, step one: type the phone number. The server says who it is before the PIN step.
@Composable
fun PhoneScreen(state: AppState) {
    val scope = rememberCoroutineScope()
    var phone by remember { mutableStateOf(state.lastPhone) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(state.loginNotice) }
    val ready = phone.count { it.isDigit() } >= MIN_PHONE_DIGITS

    fun next() {
        if (busy || !ready) return
        val clean = phone.trim()
        busy = true
        error = null
        scope.launch {
            try {
                val found = state.api.checkPhone(clean)
                state.lastPhone = clean
                state.loginNotice = null
                state.nav.push(Screen.Pin(clean, found.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                error = e.message
            } finally {
                busy = false
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Sales App", color = Burgundy, fontSize = 34.sp, fontWeight = FontWeight.Bold)
        Text("Type your phone number.", color = Muted, fontSize = 16.sp)
        Field(
            label = "Phone number",
            value = phone,
            onValueChange = {
                phone = it.filter { c -> c.isDigit() || c == '+' || c == ' ' }
                error = null
            },
            placeholder = "Phone number",
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            enabled = !busy,
        )
        // Always reserve room for the message so nothing jumps.
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.CenterStart) {
            error?.let { Say(it, error = true) }
        }
        if (busy) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Burgundy)
            }
        } else {
            BigButton("Next", enabled = ready, onClick = ::next)
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "Server address",
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !busy, role = Role.Button) { state.nav.push(Screen.ServerAddress) }
                    .padding(12.dp),
            )
        }
        if (state.apiUrl != AppConfig.DEFAULT_API_URL) {
            Text(
                "Server: ${state.apiUrl}",
                color = Muted,
                fontSize = 14.sp,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}
