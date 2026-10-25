package com.salesapp.ui.reports

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.ReportRoute
import com.salesapp.ui.navigation.ReportsRoute
import com.salesapp.ui.navigation.TargetsRoute
import com.salesapp.ui.navigation.open

// The list of reports, one report, and the monthly targets.
fun NavGraphBuilder.reportsGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<ReportsRoute> {
        ReportsPage(
            onBack = { back() },
            onOpen = { key -> nav.open(ReportRoute(key)) },
            onTargets = { nav.open(TargetsRoute) },
        )
    }
    composable<ReportRoute> { entry ->
        ReportPage(key = entry.toRoute<ReportRoute>().key, onBack = { back() })
    }
    composable<TargetsRoute> {
        TargetsPage(onBack = { back() })
    }
}
