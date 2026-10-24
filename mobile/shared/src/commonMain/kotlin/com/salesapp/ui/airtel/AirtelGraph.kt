package com.salesapp.ui.airtel

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.WalletDetailRoute
import com.salesapp.ui.navigation.WalletFormRoute
import com.salesapp.ui.navigation.WalletsRoute
import com.salesapp.ui.navigation.open

// Airtel Money wallets (Phase 7).
fun NavGraphBuilder.airtelGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<WalletsRoute> {
        WalletsPage(
            onBack = { back() },
            onOpen = { id -> nav.open(WalletDetailRoute(id)) },
            onNew = { nav.open(WalletFormRoute()) },
        )
    }
    composable<WalletDetailRoute> { entry ->
        WalletDetailPage(
            id = entry.toRoute<WalletDetailRoute>().id,
            onBack = { back() },
            onChange = { walletId -> nav.open(WalletFormRoute(walletId)) },
        )
    }
    composable<WalletFormRoute> { entry ->
        WalletFormPage(id = entry.toRoute<WalletFormRoute>().id, onBack = { back() }, onSaved = { back() })
    }
}
