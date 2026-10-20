package com.salesapp.ui.navigation

import androidx.navigation.NavController

// Go back one screen; on the first screen of the lists area leave the area instead.
fun NavController.backOr(onExit: () -> Unit) {
    if (previousBackStackEntry != null) popBackStack() else onExit()
}

// Open a screen unless it is already on top, so a double tap does not open it twice.
fun NavController.open(route: Any) {
    navigate(route) { launchSingleTop = true }
}
