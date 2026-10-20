package com.salesapp.ui.customers

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import com.salesapp.ui.navigation.CustomerDetailRoute
import com.salesapp.ui.navigation.CustomerFormRoute
import com.salesapp.ui.navigation.CustomersRoute
import com.salesapp.ui.navigation.OpeningBalanceRoute
import com.salesapp.ui.navigation.backOr
import com.salesapp.ui.navigation.open
import androidx.navigation.toRoute

fun NavGraphBuilder.customersGraph(nav: NavController, onExit: () -> Unit) {
    val back = { nav.backOr(onExit) }
    composable<CustomersRoute> {
        CustomersPage(
            onBack = back,
            onOpenCustomer = { nav.open(CustomerDetailRoute(it)) },
            onAddCustomer = { nav.open(CustomerFormRoute()) },
        )
    }
    composable<CustomerDetailRoute> { entry ->
        val route = entry.toRoute<CustomerDetailRoute>()
        CustomerDetailPage(
            id = route.id,
            onBack = back,
            onEdit = { nav.open(CustomerFormRoute(it)) },
            onEnterBalance = { customerId, code -> nav.open(OpeningBalanceRoute(customerId, code)) },
        )
    }
    composable<CustomerFormRoute> { entry ->
        CustomerFormPage(id = entry.toRoute<CustomerFormRoute>().id, onBack = back)
    }
    composable<OpeningBalanceRoute> { entry ->
        val route = entry.toRoute<OpeningBalanceRoute>()
        OpeningBalancePage(customerId = route.customerId, companyCode = route.companyCode, onBack = back)
    }
}
