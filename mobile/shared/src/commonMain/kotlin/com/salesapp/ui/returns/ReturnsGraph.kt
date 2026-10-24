package com.salesapp.ui.returns

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.NewNoteRoute
import com.salesapp.ui.navigation.NewReturnRoute
import com.salesapp.ui.navigation.ReturnDetailRoute
import com.salesapp.ui.navigation.ReturnsRoute
import com.salesapp.ui.navigation.open

// Sales returns, and the two forms made from an invoice (Phase 8).
fun NavGraphBuilder.returnsGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<ReturnsRoute> {
        ReturnsPage(onBack = { back() }, onOpen = { id -> nav.open(ReturnDetailRoute(id)) })
    }
    composable<ReturnDetailRoute> { entry ->
        ReturnDetailPage(id = entry.toRoute<ReturnDetailRoute>().id, onBack = { back() })
    }
    composable<NewReturnRoute> { entry ->
        // Back on the invoice, the return is listed and the balance is lower.
        NewReturnPage(invoiceId = entry.toRoute<NewReturnRoute>().invoiceId, onBack = { back() })
    }
    composable<NewNoteRoute> { entry ->
        NewNotePage(invoiceId = entry.toRoute<NewNoteRoute>().invoiceId, onBack = { back() })
    }
}
