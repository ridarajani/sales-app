package com.salesapp.ui.navigation

import com.salesapp.data.AlertRow
import com.salesapp.data.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

// An alert is only useful if it opens the screen that does something about it, and only for someone
// who may open it. A card that leads nowhere must do nothing at all, never crash or hint at a
// screen this job cannot see.
class AlertRoutesTest {
    private fun user(role: String) = User(id = 7, name = "Test", phone = "0991234567", role = role, companies = listOf("LV"))

    private fun alert(screen: String, id: Int? = null, report: String? = null) =
        AlertRow(key = "k", severity = "warn", title = "t", detail = "d", screen = screen, id = id, report = report)

    @Test
    fun eachAlertOpensItsOwnScreen() {
        val admin = user("admin")
        assertEquals(PlannerPlanRoute(3), alertRouteFor(alert("loadingPlan", id = 3), admin))
        assertEquals(PlansRoute, alertRouteFor(alert("loading"), admin))
        assertEquals(CustomerDetailRoute(5), alertRouteFor(alert("customer", id = 5), admin))
        assertEquals(ReturnsRoute, alertRouteFor(alert("returns"), admin))
        assertEquals(WalletsRoute, alertRouteFor(alert("airtel"), admin))
        assertEquals(MyCashRoute, alertRouteFor(alert("cash"), admin))
        assertEquals(CountCashRoute, alertRouteFor(alert("handovers"), admin))
        assertEquals(StockRoute, alertRouteFor(alert("stock"), admin))
        assertEquals(ExpiringRoute, alertRouteFor(alert("shortDated"), admin))
        assertEquals(ApprovalsRoute, alertRouteFor(alert("approvals"), admin))
        assertEquals(TripDetailRoute(9), alertRouteFor(alert("trip", id = 9), admin))
        assertEquals(OutboxProblemRoute(4), alertRouteFor(alert("outboxProblem", id = 4), admin))
        assertEquals(ReportRoute("low-stock"), alertRouteFor(alert("reports", report = "low-stock"), admin))
    }

    // The plan screen is the one that person's plans list would open for them.
    @Test
    fun aPlanOpensTheScreenForTheJob() {
        assertEquals(PlannerPlanRoute(3), alertRouteFor(alert("loadingPlan", id = 3), user("sales_manager")))
        assertEquals(WarehousePlanRoute(3), alertRouteFor(alert("loadingPlan", id = 3), user("warehouse")))
        assertEquals(TripRoute(3), alertRouteFor(alert("loadingPlan", id = 3), user("driver")))
    }

    @Test
    fun anAlertThisJobCannotOpenDoesNothing() {
        // A salesman holds cash but counts none, keeps no stock and sees no unread sales.
        val salesman = user("salesman")
        assertEquals(MyCashRoute, alertRouteFor(alert("cash"), salesman))
        assertNull(alertRouteFor(alert("handovers"), salesman))
        assertNull(alertRouteFor(alert("stock"), salesman))
        assertNull(alertRouteFor(alert("approvals"), salesman))
        assertNull(alertRouteFor(alert("outboxProblem", id = 4), salesman))

        // The warehouse sees stock and no money at all.
        val warehouse = user("warehouse")
        assertEquals(StockRoute, alertRouteFor(alert("stock"), warehouse))
        assertNull(alertRouteFor(alert("cash"), warehouse))
        assertNull(alertRouteFor(alert("airtel"), warehouse))

        // Management looks at unread sales; only Admin clears them, which the screen itself decides.
        assertEquals(OutboxProblemRoute(4), alertRouteFor(alert("outboxProblem", id = 4), user("management")))
    }

    @Test
    fun anAlertWithNothingToOpenDoesNothing() {
        val admin = user("admin")
        // No id, so there is no one plan, trip, customer or file to open.
        assertNull(alertRouteFor(alert("loadingPlan"), admin))
        assertNull(alertRouteFor(alert("trip"), admin))
        assertNull(alertRouteFor(alert("customer"), admin))
        assertNull(alertRouteFor(alert("outboxProblem"), admin))
        // No report named, and a screen this app has never heard of.
        assertNull(alertRouteFor(alert("reports"), admin))
        assertNull(alertRouteFor(alert("somethingNew", id = 1), admin))
        assertNull(alertRouteFor(alert("stock"), null))
    }

    // Search results open the same screens the lists do.
    @Test
    fun eachKindOfSearchResultOpensItsScreen() {
        val admin = user("admin")
        assertEquals(CustomerDetailRoute(1), searchRouteFor("customers", 1, admin))
        assertEquals(OrderDetailRoute(2), searchRouteFor("orders", 2, admin))
        assertEquals(InvoiceDetailRoute(3), searchRouteFor("invoices", 3, admin))
        assertEquals(PlannerPlanRoute(4), searchRouteFor("loadingPlans", 4, admin))
        assertEquals(TripDetailRoute(5), searchRouteFor("trips", 5, admin))
        assertEquals(ReturnDetailRoute(6), searchRouteFor("returns", 6, admin))
        assertEquals(UserDetailRoute(8), searchRouteFor("staff", 8, admin))
        assertNull(searchRouteFor("somethingNew", 9, admin))
    }

    // A product opens the stock screen for whoever sees stock, the form for whoever keeps the list,
    // and nothing for anybody else.
    @Test
    fun aProductOpensWhicheverProductScreenTheJobHas() {
        assertEquals(StockProductRoute(1), searchRouteFor("products", 1, user("warehouse")))
        assertEquals(StockProductRoute(1), searchRouteFor("products", 1, user("admin")))
        assertEquals(ProductFormRoute(1), searchRouteFor("products", 1, user("accounts")))
        assertNull(searchRouteFor("products", 1, user("salesman")))
        assertNull(searchRouteFor("products", 1, null))
    }
}
