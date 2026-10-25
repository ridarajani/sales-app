package com.salesapp.ui.navigation

import com.salesapp.data.AlertRow
import com.salesapp.data.User
import com.salesapp.data.canApproveStock
import com.salesapp.data.canCountCash
import com.salesapp.data.canEditProducts
import com.salesapp.data.canSeePlans
import com.salesapp.data.canSeeReturns
import com.salesapp.data.canSeeStock
import com.salesapp.data.canSeeTrips
import com.salesapp.data.canSeeUnreadSales
import com.salesapp.data.canSeeWallets
import com.salesapp.data.holdsCash
import kotlinx.serialization.Serializable

// Phase 10 part 3: the search box, the alerts, and one file of sales a phone could not read.

@Serializable
data object SearchRoute

@Serializable
data object AlertsRoute

@Serializable
data class OutboxProblemRoute(val id: Int)

// Where a plan opens from a search result or an alert, where only its id is known: the screen that
// person's plans list would open for them.
fun planRouteFor(user: User?, id: Int): Any = when (user?.role) {
    "admin", "management", "sales_manager" -> PlannerPlanRoute(id)
    "warehouse" -> WarehousePlanRoute(id)
    else -> TripRoute(id)
}

// Which screen one search result opens, or null when this person has none for it. The server only
// sends a group a person may see, so these mirror the screens the app already has.
fun searchRouteFor(groupKey: String, id: Int, user: User?): Any? = when (groupKey) {
    "customers" -> CustomerDetailRoute(id)
    "orders" -> OrderDetailRoute(id)
    "invoices" -> InvoiceDetailRoute(id)
    "loadingPlans" -> planRouteFor(user, id)
    "trips" -> TripDetailRoute(id)
    "returns" -> ReturnDetailRoute(id)
    // Whoever sees stock gets the stock screen; whoever keeps the list gets the product form.
    "products" -> when {
        user?.canSeeStock() == true -> StockProductRoute(id)
        user?.canEditProducts() == true -> ProductFormRoute(id)
        else -> null
    }
    "staff" -> UserDetailRoute(id)
    else -> null
}

// Which screen an alert opens, or null when this person cannot open it, in which case the card does
// nothing at all.
fun alertRouteFor(alert: AlertRow, user: User?): Any? {
    if (user == null) return null
    return when (alert.screen) {
        "loadingPlan" -> alert.id?.let { planRouteFor(user, it) }?.takeIf { user.canSeePlans() }
        "loading" -> PlansRoute.takeIf { user.canSeePlans() }
        "customer" -> alert.id?.let { CustomerDetailRoute(it) }
        "returns" -> ReturnsRoute.takeIf { user.canSeeReturns() }
        "airtel" -> WalletsRoute.takeIf { user.canSeeWallets() }
        "cash" -> MyCashRoute.takeIf { user.holdsCash() }
        "handovers" -> CountCashRoute.takeIf { user.canCountCash() }
        "stock" -> StockRoute.takeIf { user.canSeeStock() }
        "shortDated" -> ExpiringRoute.takeIf { user.canSeeStock() }
        "approvals" -> ApprovalsRoute.takeIf { user.canApproveStock() }
        "trip" -> alert.id?.let { TripDetailRoute(it) }?.takeIf { user.canSeeTrips() }
        "outboxProblem" -> alert.id?.let { OutboxProblemRoute(it) }?.takeIf { user.canSeeUnreadSales() }
        "reports" -> alert.report?.let { ReportRoute(it) }
        else -> null
    }
}
