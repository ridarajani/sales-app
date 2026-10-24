package com.salesapp.ui.loading

import com.salesapp.data.OpenLine
import com.salesapp.data.OpenProduct
import com.salesapp.data.PlanDetail
import com.salesapp.data.PlanItem
import com.salesapp.data.PlanProduct
import com.salesapp.data.User
import com.salesapp.data.canSeeInvoices
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The rules Phase 6 adds, and the Phase 5 fix that goes with them.
class Phase6RulesTest {
    private fun user(role: String, vararg companies: String) =
        User(id = 1, name = "Test", phone = "0991234567", role = role, companies = companies.toList())

    // Two products on the plan, one per company, each with one line that is no longer open.
    private fun planWithDroppedLines() = PlanDetail(
        id = 1,
        status = "planned",
        products = listOf(
            PlanProduct(
                productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", companyCode = "LV",
                piecesPerCarton = 12, pieces = 24, cartons = 2,
                items = listOf(PlanItem(itemId = 1, lineId = 11, orderNumber = "SO-1", customerId = 1, customerName = "Shop A", pieces = 24, cartons = 2)),
            ),
            PlanProduct(
                productId = 2, brand = "Zangafee", name = "Soap", packSize = "bar", companyCode = "ZF",
                piecesPerCarton = 10, pieces = 20, cartons = 2,
                items = listOf(PlanItem(itemId = 2, lineId = 22, orderNumber = "SO-2", customerId = 2, customerName = "Shop B", pieces = 20, cartons = 2)),
            ),
        ),
    )

    // Nothing is open any more, so both lines have dropped off the plan.
    private val nothingOpen = emptyList<OpenProduct>()

    @Test
    fun aManagerOnlySeesTheirOwnCompaniesLeftOut() {
        val state = PlannerPlanState(loading = false, plan = planWithDroppedLines(), open = nothingOpen, user = user("sales_manager", "LV", "ZT"))
        assertEquals(listOf(11), state.leftOut.map { it.lineId })
    }

    @Test
    fun theOtherManagerSeesTheirOwnLine() {
        val state = PlannerPlanState(loading = false, plan = planWithDroppedLines(), open = nothingOpen, user = user("sales_manager", "ZF", "GL"))
        assertEquals(listOf(22), state.leftOut.map { it.lineId })
    }

    @Test
    fun adminAndManagementSeeEveryCompanysLeftOut() {
        for (role in listOf("admin", "management")) {
            val state = PlannerPlanState(loading = false, plan = planWithDroppedLines(), open = nothingOpen, user = user(role))
            assertEquals(listOf(11, 22), state.leftOut.map { it.lineId }, role)
        }
    }

    @Test
    fun aLineThatIsStillOpenIsNotLeftOut() {
        val open = listOf(
            OpenProduct(
                productId = 1, companyCode = "LV", piecesPerCarton = 12,
                lines = listOf(OpenLine(lineId = 11, orderId = 1, orderNumber = "SO-1", customerId = 1, customerName = "Shop A", open = 24, current = 24)),
            ),
        )
        val state = PlannerPlanState(loading = false, plan = planWithDroppedLines(), open = open, user = user("admin"))
        assertEquals(listOf(22), state.leftOut.map { it.lineId })
    }

    @Test
    fun warehouseNeverSeesInvoices() {
        assertFalse(user("warehouse").canSeeInvoices())
    }

    @Test
    fun everyoneElseSeesInvoices() {
        for (role in listOf("admin", "management", "sales_manager", "salesman", "driver", "cashier", "accounts")) {
            assertTrue(user(role).canSeeInvoices(), role)
        }
    }
}
