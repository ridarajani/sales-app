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
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.toRoute
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.salesapp.core.Platform
import com.salesapp.core.Updater
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import com.salesapp.data.AppVersion
import com.salesapp.di.appModule
import com.salesapp.resources.Res
import com.salesapp.resources.err_signed_out
import com.salesapp.resources.update_download
import com.salesapp.resources.update_later
import com.salesapp.resources.update_text
import com.salesapp.resources.update_title
import com.salesapp.ui.customers.customersGraph
import com.salesapp.ui.airtel.airtelGraph
import com.salesapp.ui.cash.cashGraph
import com.salesapp.ui.invoices.invoicesGraph
import com.salesapp.ui.ledger.ledgerGraph
import com.salesapp.ui.reports.reportsGraph
import com.salesapp.ui.returns.returnsGraph
import com.salesapp.ui.trips.tripsGraph
import com.salesapp.ui.loading.loadingGraph
import com.salesapp.ui.navigation.HomeRoute
import com.salesapp.ui.navigation.PhoneRoute
import com.salesapp.ui.navigation.PinRoute
import com.salesapp.ui.navigation.ServerAddressRoute
import com.salesapp.ui.navigation.catalogGraph
import com.salesapp.ui.navigation.open
import com.salesapp.ui.orders.ordersGraph
import com.salesapp.ui.screens.HomePage
import com.salesapp.ui.screens.PhonePage
import com.salesapp.ui.screens.PinPage
import com.salesapp.ui.screens.ServerAddressPage
import com.salesapp.ui.screens.usersGraph
import com.salesapp.ui.stock.stockGraph
import com.salesapp.ui.theme.SalesTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filterNotNull
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.KoinApplication
import org.koin.compose.koinInject
import org.koin.dsl.koinConfiguration

// The whole app, used as is by both the Android and the iPhone app.
@Composable
fun App() {
    KoinApplication(koinConfiguration { modules(appModule) }) {
        AppContent()
    }
}

@Composable
private fun AppContent() {
    val state = koinInject<AppState>()
    val snackbar = remember { SnackbarHostState() }

    val nav = rememberNavController()
    val start = remember { if (state.user != null) HomeRoute else PhoneRoute }

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
                state.signedOut(getString(Res.string.err_signed_out))
            }
        }
    }

    // Signing in opens Home; signing out (or being signed out) opens the phone screen. Nothing to go back to.
    LaunchedEffect(nav) {
        var showingHome = start == HomeRoute
        snapshotFlow { state.user != null }.collect { signedIn ->
            if (signedIn != showingHome) {
                showingHome = signedIn
                nav.navigate(if (signedIn) HomeRoute else PhoneRoute) {
                    popUpTo(0) { inclusive = true }
                    launchSingleTop = true
                }
            }
        }
    }

    SalesTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(modifier = Modifier.fillMaxSize().safeDrawingPadding()) {
                NavHost(nav, startDestination = start) {
                    composable<PhoneRoute> {
                        PhonePage(
                            onNext = { phone, name -> nav.open(PinRoute(phone, name)) },
                            onServerAddress = { nav.open(ServerAddressRoute) },
                        )
                    }
                    composable<PinRoute> { entry ->
                        val route = entry.toRoute<PinRoute>()
                        PinPage(route.phone, route.name, onBack = { nav.popBackStack() })
                    }
                    composable<HomeRoute> { HomePage(onOpen = { nav.open(it) }) }
                    composable<ServerAddressRoute> { ServerAddressPage(onBack = { nav.popBackStack() }) }
                    usersGraph(nav)
                    stockGraph(nav)
                    ordersGraph(nav)
                    loadingGraph(nav)
                    invoicesGraph(nav)
                    cashGraph(nav)
                    airtelGraph(nav)
                    returnsGraph(nav)
                    ledgerGraph(nav)
                    tripsGraph(nav)
                    reportsGraph(nav)
                    customersGraph(nav, onExit = { nav.popBackStack() })
                    catalogGraph(nav, onExit = { nav.popBackStack() })
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
        title = { Text(stringResource(Res.string.update_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(stringResource(Res.string.update_text, latest.versionName))
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
            }, modifier = Modifier.heightIn(min = 48.dp)) { Text(stringResource(Res.string.update_download), style = MaterialTheme.typography.bodyLarge) }
        },
        dismissButton = {
            TextButton(
                enabled = progress == null,
                onClick = { offer = null },
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(stringResource(Res.string.update_later), style = MaterialTheme.typography.bodyLarge) }
        },
    )
}
