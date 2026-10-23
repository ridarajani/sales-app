package com.salesapp.ui.navigation

import com.salesapp.data.User
import kotlinx.serialization.Serializable

// Screens for loading plans.

@Serializable
data object PlansRoute

// Ask which warehouse (only when the user has two), then make the plan.
@Serializable
data object NewPlanRoute

// Pick the vehicle, driver and order lines; send to the warehouse.
@Serializable
data class PlannerPlanRoute(val id: Int)

// Pick, change, load and let out at the gate.
@Serializable
data class WarehousePlanRoute(val id: Int)

// A read-only view of the trip: the drops per shop.
@Serializable
data class TripRoute(val id: Int)

// Which screen opens a plan for this user.
fun planRouteFor(user: User?, id: Int, status: String, sent: Boolean): Any = when (user?.role) {
    "management", "sales_manager" -> if (status == "planned") PlannerPlanRoute(id) else TripRoute(id)
    "warehouse" -> WarehousePlanRoute(id)
    "admin" -> if (status == "planned" && !sent) PlannerPlanRoute(id) else WarehousePlanRoute(id)
    else -> TripRoute(id)
}
