package com.salesapp.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.salesapp.ui.customers.customersGraph

// The lists area has its own back stack. Back goes through its screens first, then calls onExit.
// onCanPopInner tells the app whether this area can still go back inside itself, so the app's
// own Back handler stays out of the way until then.
@Composable
fun ListsHost(
    start: Any,
    onExit: () -> Unit,
    onCanPopInner: (Boolean) -> Unit = {},
) {
    val navController = rememberNavController()
    val entry by navController.currentBackStackEntryAsState()
    val canPop = entry != null && navController.previousBackStackEntry != null
    val report by rememberUpdatedState(onCanPopInner)
    DisposableEffect(canPop) {
        report(canPop)
        onDispose { report(false) }
    }
    NavHost(navController = navController, startDestination = start) {
        customersGraph(navController, onExit)
        catalogGraph(navController, onExit)
    }
}
