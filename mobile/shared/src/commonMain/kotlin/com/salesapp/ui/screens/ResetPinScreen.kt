package com.salesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.resources.Res
import com.salesapp.resources.reset_pin_confirm
import com.salesapp.resources.reset_pin_new_for
import com.salesapp.resources.reset_pin_this_person
import com.salesapp.resources.reset_pin_title
import com.salesapp.resources.reset_pin_type_again
import com.salesapp.resources.reset_pin_type_new
import com.salesapp.ui.components.PinEntry
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// Type a new PIN twice for someone, then save it.
@Composable
fun ResetPinPage(
    id: Int,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ResetPinViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ResetPinEffect.Done -> onDone()
            }
        }
    }
    ResetPinScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun ResetPinScreen(state: ResetPinState, onEvent: (ResetPinEvent) -> Unit, onBack: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.reset_pin_title), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                if (state.confirming) {
                    stringResource(Res.string.reset_pin_confirm)
                } else {
                    stringResource(Res.string.reset_pin_new_for, state.userName ?: stringResource(Res.string.reset_pin_this_person))
                },
                style = MaterialTheme.typography.titleLarge,
                color = scheme.onSurface,
            )
            Text(
                stringResource(if (state.confirming) Res.string.reset_pin_type_again else Res.string.reset_pin_type_new),
                style = MaterialTheme.typography.bodyMedium,
                color = scheme.onSurfaceVariant,
            )
            state.error?.let { Say(it, error = true) }
            PinEntry(pin = state.pin, onChange = { onEvent(ResetPinEvent.PinChanged(it)) }, enabled = !state.busy)
            if (state.busy) CircularProgressIndicator(color = scheme.primary)
        }
    }
}

@Preview
@Composable
private fun ResetPinPreview() {
    SalesTheme {
        ResetPinScreen(ResetPinState(userName = "Chisomo Phiri", pin = "12"), onEvent = {}, onBack = {})
    }
}
