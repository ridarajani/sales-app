package com.salesapp.ui.loading

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.delivery.ConfirmDeliveryPage
import com.salesapp.ui.navigation.ConfirmDeliveryRoute
import com.salesapp.ui.navigation.NewPlanRoute
import com.salesapp.ui.navigation.PlannerPlanRoute
import com.salesapp.ui.navigation.PlansRoute
import com.salesapp.ui.navigation.TripRoute
import com.salesapp.ui.navigation.WarehousePlanRoute
import com.salesapp.ui.navigation.open

// The plans list, a new plan, one plan as the planner, the warehouse or the driver sees it,
// and the driver confirming one shop's drop.
fun NavGraphBuilder.loadingGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<PlansRoute> {
        PlansPage(
            onBack = { back() },
            onOpen = { nav.open(it) },
            onNewPlan = { nav.open(NewPlanRoute) },
        )
    }
    composable<NewPlanRoute> {
        NewPlanPage(
            onBack = { back() },
            // The new plan replaces the warehouse question, so back goes to the list.
            onCreated = { id ->
                nav.navigate(PlannerPlanRoute(id)) { popUpTo<NewPlanRoute> { inclusive = true } }
            },
        )
    }
    composable<PlannerPlanRoute> { entry ->
        PlannerPlanPage(
            id = entry.toRoute<PlannerPlanRoute>().id,
            onBack = { back() },
            onDeleted = { back() },
            onOpen = { nav.open(it) },
        )
    }
    composable<WarehousePlanRoute> { entry ->
        WarehousePlanPage(id = entry.toRoute<WarehousePlanRoute>().id, onBack = { back() })
    }
    composable<TripRoute> { entry ->
        TripPage(id = entry.toRoute<TripRoute>().id, onBack = { back() }, onOpen = { nav.open(it) })
    }
    composable<ConfirmDeliveryRoute> { entry ->
        val route = entry.toRoute<ConfirmDeliveryRoute>()
        ConfirmDeliveryPage(
            planId = route.planId,
            customerId = route.customerId,
            onBack = { back() },
            // Back on the trip, the drop shows as delivered.
            onDone = { back() },
        )
    }
}
