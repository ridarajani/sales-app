package com.salesapp.ui.search

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.salesapp.ui.alerts.AlertsPage
import com.salesapp.ui.navigation.AlertsRoute
import com.salesapp.ui.navigation.SearchRoute
import com.salesapp.ui.navigation.open

// The search box and the alerts. Both decide for themselves what a tap opens, since that depends on
// the person's job as much as on what was tapped.
fun NavGraphBuilder.searchGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<SearchRoute> {
        SearchPage(onBack = { back() }, onOpen = { nav.open(it) })
    }
    composable<AlertsRoute> {
        AlertsPage(onBack = { back() }, onOpen = { nav.open(it) })
    }
}
