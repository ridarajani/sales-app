package com.salesapp.ui.ledger

import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.LedgerRoute

// The customer ledger card (Phase 8).
fun NavGraphBuilder.ledgerGraph(nav: NavController) {
    composable<LedgerRoute> { entry ->
        val route = entry.toRoute<LedgerRoute>()
        LedgerPage(
            customerId = route.customerId,
            companyCode = route.companyCode,
            onBack = { nav.popBackStack() },
        )
    }
}
