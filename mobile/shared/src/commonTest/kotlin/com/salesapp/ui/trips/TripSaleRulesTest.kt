package com.salesapp.ui.trips

import com.salesapp.data.OutboxSale
import com.salesapp.data.PackCompany
import com.salesapp.data.PackCustomer
import com.salesapp.data.PackPrice
import com.salesapp.data.TripDetail
import com.salesapp.data.TripLine
import com.salesapp.data.TripOutbox
import com.salesapp.data.TripSaleLineQty
import com.salesapp.data.TripSaleRequest
import com.salesapp.data.User
import com.salesapp.data.canLoadTrips
import com.salesapp.data.canPlanTrips
import com.salesapp.data.canSeeTrips
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// The rules the sale screen has to hold to with no network, before the server ever sees it.
class TripSaleRulesTest {
    private fun user(role: String) = User(id = 1, name = "Test", phone = "0991234567", role = role)

    private val shop = PackCustomer(
        id = 1,
        name = "Salima Shop",
        channel = "upcountry",
        lastInvoiceType = "plain",
        companies = listOf(PackCompany("LV", 25_000.0), PackCompany("ZT", 5_000.0)),
        lastPrices = listOf(PackPrice(1, 25_000.0)),
    )
    private val kiosk = PackCustomer(id = 2, name = "Cash Kiosk", channel = "upcountry", cashOnly = true)

    private val milk = TripLine(productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", piecesPerCarton = 12, loaded = 120, sold = 48, onVehicle = 72)

    private fun state(customer: PackCustomer, cartons: Int? = null, onVehicle: Int = 72) = TripSaleState(
        loading = false,
        customers = listOf(shop, kiosk),
        customerId = customer.id,
        lines = listOf(SaleLine(milk, onVehicle = onVehicle, cartons = cartons, price = 25_000)),
    )

    // A cash-only shop cannot buy on account.
    @Test
    fun aCashOnlyShopIsNotOfferedOnAccount() {
        assertTrue(state(shop).canUseCredit)
        assertFalse(state(kiosk).canUseCredit)
    }

    @Test
    fun noMoreThanIsOnTheVanCanBeSold() {
        // 6 cartons of 12 is 72, exactly what is on the van.
        assertFalse(state(shop, cartons = 6).lines[0].tooMuch)
        assertTrue(state(shop, cartons = 7).lines[0].tooMuch)
    }

    // What this phone has already sold but not sent comes off the van first.
    @Test
    fun whatIsWaitingOnThePhoneComesOffTheVan() {
        val outbox = TripOutbox(
            tripId = 7,
            sales = listOf(
                OutboxSale(
                    TripSaleRequest(
                        requestId = "a",
                        customerId = 1,
                        lines = listOf(TripSaleLineQty(productId = 1, cartons = 4, pricePerCarton = 25_000)),
                    ),
                ),
            ),
        )
        val waiting = outbox.soldPieces(mapOf(1 to 12))
        val left = milk.onVehicle - (waiting[1] ?: 0)
        assertEquals(24, left)
        // With only 24 left, two cartons are fine and three are not.
        assertFalse(state(shop, cartons = 2, onVehicle = left).lines[0].tooMuch)
        assertTrue(state(shop, cartons = 3, onVehicle = left).lines[0].tooMuch)
    }

    @Test
    fun theLineAddsUpByTheCarton() {
        val line = SaleLine(milk, onVehicle = 72, cartons = 2, pieces = 6, price = 24_000)
        // 2 cartons and 6 pieces of a 12-piece carton is two and a half cartons.
        assertEquals(30, line.wanted)
        assertEquals(60_000L, line.amount)
    }

    @Test
    fun nothingTypedIsNotWorthSending() {
        assertFalse(state(shop).anything)
        assertTrue(state(shop, cartons = 1).anything)
    }

    // The customer's own last choice and price are what the form starts on.
    @Test
    fun theShopsLastPriceAndInvoiceTypeAreTheStartingPoint() {
        assertEquals(25_000L, shop.lastPriceOf(1))
        assertEquals(null, shop.lastPriceOf(99))
        assertEquals("plain", shop.lastInvoiceType)
        assertEquals(30_000.0, shop.owes)
    }

    @Test
    fun airtelNeedsItsReference() {
        val airtel = state(shop, cartons = 1).copy(payment = "airtel")
        assertTrue(airtel.needsReference)
        assertFalse(airtel.copy(payment = "cash").needsReference)
        assertFalse(airtel.copy(payment = "credit").needsReference)
    }

    // Closing waits until the phone has nothing left to send.
    @Test
    fun closingIsBlockedWhileAnythingWaits() {
        val trip = TripDetail(id = 7, number = "TRP-000007", status = "out", canClose = true, canSell = true)
        val clear = TripDetailState(loading = false, trip = trip)
        assertTrue(clear.canClose)
        assertFalse(clear.blockedByOutbox)

        val waiting = clear.copy(
            outbox = TripOutbox(tripId = 7, sales = listOf(OutboxSale(TripSaleRequest(requestId = "a", customerId = 1)))),
        )
        assertFalse(waiting.canClose)
        assertTrue(waiting.blockedByOutbox)
    }

    @Test
    fun whoSeesPlansAndLoadsTrips() {
        for (role in listOf("admin", "management", "sales_manager", "salesman", "warehouse", "driver", "cashier", "accounts")) {
            assertTrue(user(role).canSeeTrips(), role)
        }
        for (role in listOf("admin", "management", "sales_manager")) assertTrue(user(role).canPlanTrips(), role)
        for (role in listOf("salesman", "warehouse", "driver", "cashier", "accounts")) assertFalse(user(role).canPlanTrips(), role)
        assertTrue(user("warehouse").canLoadTrips())
        assertFalse(user("sales_manager").canLoadTrips())
    }

    // The warehouse is sent no money at all, so the screens show none.
    @Test
    fun theWarehouseSeesNoMoneyOnATrip() {
        val withMoney = TripDetail(id = 7, number = "TRP-000007", float = 100_000.0, salesTotal = 50_000.0)
        assertTrue(withMoney.showsMoney)
        assertFalse(withMoney.copy(float = null).showsMoney)
    }

    // More spent than the float means the extra comes out of the cash from sales.
    @Test
    fun spendingMoreThanTheFloatIsCalledOut() {
        val trip = TripDetail(id = 7, number = "TRP-000007", float = 100_000.0, expensesTotal = 30_000.0)
        assertFalse(trip.overspent)
        assertEquals(70_000.0, trip.floatLeft)
        assertTrue(trip.copy(expensesTotal = 120_000.0).overspent)
    }
}
