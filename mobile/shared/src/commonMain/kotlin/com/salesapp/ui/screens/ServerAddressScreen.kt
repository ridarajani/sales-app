package com.salesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.resources.Res
import com.salesapp.resources.server_label
import com.salesapp.resources.server_save
import com.salesapp.resources.server_testing_only
import com.salesapp.resources.server_title
import com.salesapp.resources.server_use_default
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun ServerAddressPage(
    onBack: () -> Unit,
    viewModel: ServerAddressViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                ServerAddressEffect.Done -> onBack()
            }
        }
    }
    ServerAddressScreen(state, viewModel::onEvent, onBack)
}

// Lets a tester point the app at a different server.
@Composable
fun ServerAddressScreen(state: ServerAddressState, onEvent: (ServerAddressEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.server_title), onBack = onBack)
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Say(stringResource(Res.string.server_testing_only))
            state.error?.let { Say(it, error = true) }
            Field(
                label = stringResource(Res.string.server_label),
                value = state.url,
                onValueChange = { onEvent(ServerAddressEvent.UrlChanged(it)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            PrimaryButton(stringResource(Res.string.server_save), onClick = { onEvent(ServerAddressEvent.Save) })
            SecondaryButton(stringResource(Res.string.server_use_default), onClick = { onEvent(ServerAddressEvent.UseDefault) })
        }
    }
}

@Preview
@Composable
private fun ServerAddressPreview() {
    SalesTheme {
        ServerAddressScreen(ServerAddressState(url = "http://192.168.1.10:8000"), onEvent = {}, onBack = {})
    }
}
