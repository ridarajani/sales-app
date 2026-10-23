package com.salesapp.ui.orders

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.BookOrderRoute
import com.salesapp.ui.navigation.ChangeOrderRoute
import com.salesapp.ui.navigation.NewOrderRoute
import com.salesapp.ui.navigation.OrderDetailRoute
import com.salesapp.ui.navigation.OrdersRoute
import com.salesapp.ui.navigation.open

// The orders list, booking a new order, one order and changing it.
fun NavGraphBuilder.ordersGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<OrdersRoute> {
        OrdersPage(
            onBack = { back() },
            onOpenOrder = { nav.open(OrderDetailRoute(it)) },
            onNewOrder = { nav.open(NewOrderRoute) },
        )
    }
    composable<NewOrderRoute> {
        NewOrderPage(onBack = { back() }, onPickCustomer = { nav.open(BookOrderRoute(it)) })
    }
    composable<BookOrderRoute> { entry ->
        BookOrderPage(
            customerId = entry.toRoute<BookOrderRoute>().customerId,
            onBack = { back() },
            // The booked order replaces the customer picker and the form, so back goes to where booking started.
            onBooked = { id ->
                nav.navigate(OrderDetailRoute(id)) { popUpTo<NewOrderRoute> { inclusive = true } }
            },
        )
    }
    composable<OrderDetailRoute> { entry ->
        OrderDetailPage(
            id = entry.toRoute<OrderDetailRoute>().id,
            onBack = { back() },
            onChange = { nav.open(ChangeOrderRoute(it)) },
        )
    }
    composable<ChangeOrderRoute> { entry ->
        ChangeOrderPage(
            id = entry.toRoute<ChangeOrderRoute>().id,
            onBack = { back() },
            // Pops only the change form, so a second Done does nothing once it is gone.
            onDone = { nav.popBackStack<ChangeOrderRoute>(inclusive = true) },
        )
    }
}
