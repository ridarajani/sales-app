package com.salesapp.ui.invoices

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.InvoiceDetailRoute
import com.salesapp.ui.navigation.InvoicesRoute
import com.salesapp.ui.navigation.open

// The invoice register and one invoice.
fun NavGraphBuilder.invoicesGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<InvoicesRoute> {
        InvoicesPage(onBack = { back() }, onOpen = { id -> nav.open(InvoiceDetailRoute(id)) })
    }
    composable<InvoiceDetailRoute> { entry ->
        InvoiceDetailPage(id = entry.toRoute<InvoiceDetailRoute>().id, onBack = { back() })
    }
}
