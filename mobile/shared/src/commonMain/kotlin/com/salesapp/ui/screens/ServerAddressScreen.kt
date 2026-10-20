package com.salesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.salesapp.core.AppConfig
import com.salesapp.data.AppState
import com.salesapp.ui.components.BigButton
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.TopBar

// Lets a tester point the app at a different server.
@Composable
fun ServerAddressScreen(state: AppState) {
    var url by remember { mutableStateOf(state.apiUrl) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar("Server address", onBack = { state.nav.pop() })
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Say("Only for testing. Ask the person who set up the app.")
            error?.let { Say(it, error = true) }
            Field(
                label = "Server address",
                value = url,
                onValueChange = { url = it },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            BigButton("Save", onClick = {
                val clean = url.trim().trimEnd('/')
                if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                    error = "The address must start with http:// or https://"
                } else {
                    state.changeApiUrl(clean)
                    state.message = "Server address saved."
                    state.nav.pop()
                }
            })
            SecondaryButton("Use default", onClick = {
                state.changeApiUrl(null)
                url = AppConfig.DEFAULT_API_URL
                state.message = "Using the default address."
                state.nav.pop()
            })
        }
    }
}
