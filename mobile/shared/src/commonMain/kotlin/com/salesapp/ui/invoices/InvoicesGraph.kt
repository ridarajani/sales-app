package com.salesapp.ui.invoices

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.InvoiceDetailRoute
import com.salesapp.ui.navigation.CollectMoneyRoute
import com.salesapp.ui.navigation.InvoicesRoute
import com.salesapp.ui.navigation.LedgerRoute
import com.salesapp.ui.navigation.NewNoteRoute
import com.salesapp.ui.navigation.NewReturnRoute
import com.salesapp.ui.navigation.open

// The invoice register and one invoice.
fun NavGraphBuilder.invoicesGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<InvoicesRoute> {
        InvoicesPage(onBack = { back() }, onOpen = { id -> nav.open(InvoiceDetailRoute(id)) })
    }
    composable<InvoiceDetailRoute> { entry ->
        InvoiceDetailPage(
            id = entry.toRoute<InvoiceDetailRoute>().id,
            onBack = { back() },
            // Straight to the payment, with the shop, company and invoice already picked.
            onCollect = { customerId, companyCode, invoiceId ->
                nav.open(CollectMoneyRoute(customerId = customerId, companyCode = companyCode, invoiceId = invoiceId))
            },
            onReturn = { invoiceId -> nav.open(NewReturnRoute(invoiceId)) },
            onNote = { invoiceId -> nav.open(NewNoteRoute(invoiceId)) },
            onLedger = { customerId, companyCode -> nav.open(LedgerRoute(customerId = customerId, companyCode = companyCode)) },
        )
    }
}
