package com.salesapp.ui.loading

import com.salesapp.data.User
import com.salesapp.data.isNewFor
import com.salesapp.ui.navigation.PlannerPlanRoute
import com.salesapp.ui.navigation.TripRoute
import com.salesapp.ui.navigation.WarehousePlanRoute
import com.salesapp.ui.navigation.planRouteFor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadingRulesTest {
    private fun user(role: String) = User(id = 1, name = "Test", phone = "0991234567", role = role)

    @Test
    fun plannersOpenThePlanWhilePlannedAndTheTripAfter() {
        for (role in listOf("management", "sales_manager")) {
            assertEquals(PlannerPlanRoute(5), planRouteFor(user(role), 5, "planned", sent = false))
            assertEquals(PlannerPlanRoute(5), planRouteFor(user(role), 5, "planned", sent = true))
            assertEquals(TripRoute(5), planRouteFor(user(role), 5, "loaded", sent = true))
            assertEquals(TripRoute(5), planRouteFor(user(role), 5, "out", sent = true))
        }
    }

    @Test
    fun warehouseAlwaysOpensTheWarehouseScreen() {
        for (status in listOf("planned", "loaded", "out", "done")) {
            assertEquals(WarehousePlanRoute(7), planRouteFor(user("warehouse"), 7, status, sent = true))
        }
    }

    @Test
    fun adminPlansUntilSentThenLoads() {
        assertEquals(PlannerPlanRoute(3), planRouteFor(user("admin"), 3, "planned", sent = false))
        assertEquals(WarehousePlanRoute(3), planRouteFor(user("admin"), 3, "planned", sent = true))
        assertEquals(WarehousePlanRoute(3), planRouteFor(user("admin"), 3, "loaded", sent = true))
    }

    @Test
    fun everyoneElseSeesTheTrip() {
        for (role in listOf("driver", "accounts", "cashier", "salesman")) {
            assertEquals(TripRoute(9), planRouteFor(user(role), 9, "loaded", sent = true))
        }
        assertEquals(TripRoute(9), planRouteFor(null, 9, "planned", sent = false))
    }

    @Test
    fun warehouseHearsAboutPlannerChanges() {
        assertTrue(user("warehouse").isNewFor(byWarehouse = false))
        assertFalse(user("warehouse").isNewFor(byWarehouse = true))
    }

    @Test
    fun adminHearsAboutEveryChange() {
        assertTrue(user("admin").isNewFor(byWarehouse = false))
        assertTrue(user("admin").isNewFor(byWarehouse = true))
    }

    @Test
    fun othersHearAboutWarehouseChanges() {
        for (role in listOf("management", "sales_manager", "accounts", "cashier", "driver")) {
            assertTrue(user(role).isNewFor(byWarehouse = true), role)
            assertFalse(user(role).isNewFor(byWarehouse = false), role)
        }
    }
}
