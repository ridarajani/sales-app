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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.resources.Res
import com.salesapp.resources.login_next
import com.salesapp.resources.login_phone_label
import com.salesapp.resources.login_server_address
import com.salesapp.resources.login_server_line
import com.salesapp.resources.login_title
import com.salesapp.resources.login_type_phone
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun PhonePage(
    onNext: (phone: String, name: String) -> Unit,
    onServerAddress: () -> Unit,
    viewModel: PhoneViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is PhoneEffect.GoToPin -> onNext(effect.phone, effect.name)
                PhoneEffect.OpenServerAddress -> onServerAddress()
            }
        }
    }
    PhoneScreen(state, viewModel::onEvent)
}

// Sign in, step one: type the phone number. The server says who it is before the PIN step.
@Composable
fun PhoneScreen(state: PhoneState, onEvent: (PhoneEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(stringResource(Res.string.login_title), color = scheme.primary, style = MaterialTheme.typography.headlineLarge)
        Text(stringResource(Res.string.login_type_phone), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
        Field(
            label = stringResource(Res.string.login_phone_label),
            value = state.phone,
            onValueChange = { onEvent(PhoneEvent.PhoneChanged(it)) },
            placeholder = stringResource(Res.string.login_phone_label),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            enabled = !state.busy,
        )
        // Always reserve room for the message so nothing jumps.
        Box(modifier = Modifier.fillMaxWidth().heightIn(min = 80.dp), contentAlignment = Alignment.CenterStart) {
            state.error?.let { Say(it, error = true) }
        }
        if (state.busy) {
            Box(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = scheme.primary)
            }
        } else {
            PrimaryButton(stringResource(Res.string.login_next), enabled = state.ready, onClick = { onEvent(PhoneEvent.Next) })
        }
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                stringResource(Res.string.login_server_address),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(enabled = !state.busy, role = Role.Button) { onEvent(PhoneEvent.OpenServerAddress) }
                    .padding(12.dp),
            )
        }
        if (state.isCustomServer) {
            Text(
                stringResource(Res.string.login_server_line, state.apiUrl),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Preview
@Composable
private fun PhonePreview() {
    SalesTheme {
        PhoneScreen(
            PhoneState(phone = "0991 234 567", ready = true, apiUrl = "http://192.168.1.10:8000", isCustomServer = true),
            onEvent = {},
        )
    }
}
