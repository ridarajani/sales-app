package com.salesapp

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.core.Platform
import com.salesapp.core.Updater
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import com.salesapp.data.AppVersion
import com.salesapp.data.Screen
import com.salesapp.ui.screens.AddUserScreen
import com.salesapp.ui.screens.HomeScreen
import com.salesapp.ui.screens.PhoneScreen
import com.salesapp.ui.screens.PinScreen
import com.salesapp.ui.screens.ResetPinScreen
import com.salesapp.ui.screens.ServerAddressScreen
import com.salesapp.ui.screens.UserDetailScreen
import com.salesapp.ui.screens.UsersScreen
import com.salesapp.ui.theme.SalesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull

// The whole app, used as is by both the Android and the iPhone app.
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun App() {
    val state = remember { AppState() }
    val snackbar = remember { SnackbarHostState() }

    // Phone Back button (Android) goes to the previous screen.
    BackHandler(enabled = state.nav.canGoBack) { state.nav.pop() }

    // Show short messages at the bottom, one at a time.
    LaunchedEffect(Unit) {
        snapshotFlow { state.message }.filterNotNull().collect { text ->
            state.message = null
            snackbar.showSnackbar(text)
        }
    }

    // If we are signed in already, check in the background that the sign-in is still good.
    LaunchedEffect(Unit) {
        if (state.session.token == null) return@LaunchedEffect
        val startToken = state.session.token
        try {
            val fresh = state.api.me()
            // Ignore the answer if the person signed out or in again meanwhile.
            if (state.session.token == startToken && startToken != null) {
                state.session.saveUser(fresh)
                state.user = fresh
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isSignedOut && state.session.token == startToken) {
                state.signedOut("You were signed out. Sign in again.")
            }
        }
    }

    SalesTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                when (val screen = state.nav.current) {
                    Screen.Phone -> PhoneScreen(state)
                    is Screen.Pin -> PinScreen(state, screen.phone, screen.name)
                    Screen.Home -> HomeScreen(state)
                    Screen.Users -> UsersScreen(state)
                    Screen.AddUser -> AddUserScreen(state)
                    is Screen.UserDetail -> UserDetailScreen(state, screen.id)
                    is Screen.ResetPin -> ResetPinScreen(state, screen.id)
                    Screen.ServerAddress -> ServerAddressScreen(state)
                }
                SnackbarHost(snackbar, modifier = Modifier.align(Alignment.BottomCenter))
                UpdatePrompt(state)
            }
        }
    }
}

// Android only: asks the server for a newer version and offers to download it.
@Composable
private fun UpdatePrompt(state: AppState) {
    if (!Platform.isAndroid) return
    var offer by remember { mutableStateOf<AppVersion?>(null) }
    var progress by remember { mutableStateOf<Float?>(null) }
    var problem by remember { mutableStateOf<String?>(null) }
    val updater = remember { Updater() }

    LaunchedEffect(Unit) {
        delay(1000)
        try {
            val latest = state.api.appVersion("android")
            if (latest != null && latest.versionCode > Platform.appVersionCode && (latest.downloadUrl.isNotBlank() || latest.downloadPath.isNotBlank())) {
                offer = latest
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // No news is fine: stay quiet if the server is unreachable or has no version.
        }
    }

    val latest = offer ?: return
    AlertDialog(
        onDismissRequest = { if (progress == null) offer = null },
        title = { Text("A new version is ready") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text("Version ${latest.versionName}. Download it now, then tap Install.")
                progress?.let {
                    LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp))
                }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 12.dp)) }
            }
        },
        confirmButton = {
            TextButton(enabled = progress == null, onClick = {
                problem = null
                progress = 0f
                updater.downloadAndInstall(
                    url = if (latest.downloadPath.isNotEmpty()) {
                        state.apiUrl.trimEnd('/') + latest.downloadPath
                    } else {
                        latest.downloadUrl
                    },
                    versionName = latest.versionName,
                    onProgress = { progress = it },
                    onDone = { progress = null },
                    onError = {
                        problem = it
                        progress = null
                    },
                )
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Download", fontSize = 16.sp) }
        },
        dismissButton = {
            TextButton(
                enabled = progress == null,
                onClick = { offer = null },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Later", fontSize = 16.sp) }
        },
    )
}
