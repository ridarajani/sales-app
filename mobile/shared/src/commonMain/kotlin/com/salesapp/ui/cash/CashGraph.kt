package com.salesapp.ui.cash

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.CollectMoneyRoute
import com.salesapp.ui.navigation.CountCashRoute
import com.salesapp.ui.navigation.ExpensesRoute
import com.salesapp.ui.navigation.HandoverDetailRoute
import com.salesapp.ui.navigation.MyCashRoute
import com.salesapp.ui.navigation.NewExpenseRoute
import com.salesapp.ui.navigation.PaymentDetailRoute
import com.salesapp.ui.navigation.PaymentsRoute
import com.salesapp.ui.navigation.open

// Payments, expenses and cash (Phase 7).
fun NavGraphBuilder.cashGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<CollectMoneyRoute> { entry ->
        val route = entry.toRoute<CollectMoneyRoute>()
        CollectMoneyPage(
            customerId = route.customerId,
            companyCode = route.companyCode,
            invoiceId = route.invoiceId,
            onBack = { back() },
        )
    }
    composable<PaymentsRoute> {
        PaymentsPage(onBack = { back() }, onOpen = { id -> nav.open(PaymentDetailRoute(id)) })
    }
    composable<PaymentDetailRoute> { entry ->
        PaymentDetailPage(id = entry.toRoute<PaymentDetailRoute>().id, onBack = { back() })
    }
    composable<ExpensesRoute> {
        ExpensesPage(onBack = { back() }, onNew = { nav.open(NewExpenseRoute) })
    }
    composable<NewExpenseRoute> {
        // Back on the list, the new expense is there.
        NewExpensePage(onBack = { back() }, onSaved = { back() })
    }
    composable<MyCashRoute> {
        MyCashPage(onBack = { back() })
    }
    composable<CountCashRoute> {
        CountCashPage(onBack = { back() }, onOpen = { id -> nav.open(HandoverDetailRoute(id)) })
    }
    composable<HandoverDetailRoute> { entry ->
        HandoverDetailPage(id = entry.toRoute<HandoverDetailRoute>().id, onBack = { back() })
    }
}
