package com.salesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Modifier
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Type a new PIN twice for someone, then save it.
@Composable
fun ResetPinScreen(state: AppState, id: Int) {
    val name = state.userRows.firstOrNull { it.id == id }?.name ?: "this person"
    val scope = rememberCoroutineScope()
    var first by remember { mutableStateOf("") }
    var pin by remember { mutableStateOf("") }
    var confirming by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var stepping by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun save(newPin: String) {
        if (busy) return
        busy = true
        error = null
        scope.launch {
            try {
                state.api.resetPin(id, newPin)
                state.message = "PIN changed."
                state.nav.pop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                if (e.isSignedOut) state.signedOut("You were signed out. Sign in again.") else error = e.message
                pin = ""
            } finally {
                busy = false
            }
        }
    }

    fun onPin(value: String) {
        if (busy || stepping) return
        pin = value
        if (value.length < PIN_LENGTH) return
        if (!confirming) {
            // Let the fourth dot show before moving on.
            stepping = true
            scope.launch {
                delay(150)
                first = value
                pin = ""
                confirming = true
                error = null
                stepping = false
            }
        } else if (value == first) {
            save(value)
        } else {
            stepping = true
            scope.launch {
                delay(150)
                error = "PINs don't match. Try again."
                first = ""
                pin = ""
                confirming = false
                stepping = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("Reset PIN", onBack = { state.nav.pop() })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (confirming) "Confirm PIN" else "New PIN for $name",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Burgundy,
            )
            Text(if (confirming) "Type it again" else "Type the new PIN", fontSize = 16.sp)
            error?.let { Say(it, error = true) }
            PinEntry(pin = pin, onChange = ::onPin, enabled = !busy)
            if (busy) CircularProgressIndicator(color = Burgundy)
        }
    }
}
