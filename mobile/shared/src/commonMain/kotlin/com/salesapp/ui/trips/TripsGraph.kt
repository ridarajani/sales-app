package com.salesapp.ui.trips

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.CloseTripRoute
import com.salesapp.ui.navigation.PlanTripRoute
import com.salesapp.ui.navigation.TripDetailRoute
import com.salesapp.ui.navigation.TripExpenseRoute
import com.salesapp.ui.navigation.TripSaleRoute
import com.salesapp.ui.navigation.TripsRoute
import com.salesapp.ui.navigation.open

// Trips: the list, one trip, planning, selling, expenses and closing.
fun NavGraphBuilder.tripsGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<TripsRoute> {
        TripsPage(
            onBack = { back() },
            onOpen = { id -> nav.open(TripDetailRoute(id)) },
            onPlan = { nav.open(PlanTripRoute()) },
        )
    }
    composable<TripDetailRoute> { entry ->
        TripDetailPage(
            id = entry.toRoute<TripDetailRoute>().id,
            onBack = { back() },
            onSell = { tripId -> nav.open(TripSaleRoute(tripId)) },
            onDeliver = { tripId, orderId -> nav.open(TripSaleRoute(tripId, orderId)) },
            onExpense = { tripId -> nav.open(TripExpenseRoute(tripId)) },
            onClose = { tripId -> nav.open(CloseTripRoute(tripId)) },
            onEdit = { tripId -> nav.open(PlanTripRoute(tripId)) },
        )
    }
    composable<PlanTripRoute> { entry ->
        val route = entry.toRoute<PlanTripRoute>()
        PlanTripPage(
            tripId = route.id,
            onBack = { back() },
            // A new trip opens straight away; a changed one goes back to itself.
            onSaved = { tripId ->
                if (route.id == null) {
                    nav.navigate(TripDetailRoute(tripId)) { popUpTo<PlanTripRoute> { inclusive = true } }
                } else {
                    back()
                }
            },
            onDeleted = { nav.navigate(TripsRoute) { popUpTo<TripsRoute> { inclusive = true } } },
        )
    }
    composable<TripSaleRoute> { entry ->
        val route = entry.toRoute<TripSaleRoute>()
        TripSalePage(tripId = route.tripId, orderId = route.orderId, onBack = { back() })
    }
    composable<TripExpenseRoute> { entry ->
        TripExpensePage(tripId = entry.toRoute<TripExpenseRoute>().tripId, onBack = { back() }, onSaved = { back() })
    }
    composable<CloseTripRoute> { entry ->
        CloseTripPage(tripId = entry.toRoute<CloseTripRoute>().tripId, onBack = { back() }, onClosed = { back() })
    }
}
