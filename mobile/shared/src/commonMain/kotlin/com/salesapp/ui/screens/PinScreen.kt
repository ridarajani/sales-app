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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.resources.Res
import com.salesapp.resources.pin_hello
import com.salesapp.resources.pin_not_you
import com.salesapp.resources.pin_title
import com.salesapp.resources.pin_type
import com.salesapp.ui.components.Avatar
import com.salesapp.ui.components.PIN_LENGTH
import com.salesapp.ui.components.PinEntry
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun PinPage(
    phone: String,
    name: String,
    onBack: () -> Unit,
    viewModel: PinViewModel = koinViewModel { parametersOf(phone, name) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    PinScreen(state, viewModel::onEvent, onBack)
}

// Sign in, step two: greet the person by name and take the four-digit PIN.
@Composable
fun PinScreen(state: PinState, onEvent: (PinEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.pin_title), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Avatar(state.name, size = 56.dp)
                Text(
                    stringResource(Res.string.pin_hello, state.name.trim().substringBefore(' ')),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(top = 10.dp),
                )
                Text(stringResource(Res.string.pin_type), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            PinEntry(
                pin = state.pin,
                enabled = !state.busy,
                belowDots = {
                    // Always reserve room for the message so the keys never move.
                    Box(modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp), contentAlignment = Alignment.Center) {
                        state.error?.let { Say(it, error = true) }
                    }
                },
                onChange = { onEvent(PinEvent.PinChanged(it)) },
            )
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp), contentAlignment = Alignment.Center) {
                if (state.busy) CircularProgressIndicator(color = scheme.primary)
            }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(
                    stringResource(Res.string.pin_not_you),
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .clickable(enabled = !state.busy, role = Role.Button, onClick = onBack)
                        .padding(12.dp),
                )
            }
        }
    }
}

@Preview
@Composable
private fun PinPreview() {
    SalesTheme {
        PinScreen(PinState(name = "Mary Banda", pin = "12"), onEvent = {}, onBack = {})
    }
}
