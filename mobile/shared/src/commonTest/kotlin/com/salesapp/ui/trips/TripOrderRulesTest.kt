package com.salesapp.ui.trips

import com.salesapp.core.Files
import com.salesapp.data.OutboxSale
import com.salesapp.data.PieceQty
import com.salesapp.data.TripDetail
import com.salesapp.data.TripLine
import com.salesapp.data.TripOrder
import com.salesapp.data.TripOrderLine
import com.salesapp.data.TripOutbox
import com.salesapp.data.TripOutboxStore
import com.salesapp.data.TripPack
import com.salesapp.data.TripSaleLineQty
import com.salesapp.data.TripSaleRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

// Phase 9 part 2: a trip carries orders booked ahead. Their goods ride on the same van but belong
// to those orders, so a sale at a stop may only take what is left over.
class TripOrderRulesTest {
    // 120 pieces loaded, 48 of them for the order, so 72 are on the van and 24 are free to sell.
    private val milk = TripLine(
        productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", piecesPerCarton = 12,
        planned = 120, loaded = 120, sold = 48, onVehicle = 72,
        forOrders = 48, forOrdersQty = qty(48), forSale = 24, forSaleQty = qty(24),
    )

    private val orderLine = TripOrderLine(
        orderLineId = 11, productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", companyCode = "LV",
        piecesPerCarton = 12, pricePerCarton = 25_000.0, pieces = 48, onVehicle = 48, toDeliver = 48,
        toDeliverQty = qty(48), amount = 100_000.0,
    )

    private val order = TripOrder(
        id = 31, number = "LV-ORD-000031", customerId = 1, customerName = "Salima Shop", area = "Salima",
        invoiceType = "plain", wantedOn = "2026-10-27", lines = listOf(orderLine),
    )

    private fun qty(pieces: Int, perCarton: Int = 12) =
        PieceQty(pieces = pieces, cartons = pieces / perCarton, loosePieces = pieces % perCarton)

    private fun trip(lines: List<TripLine> = listOf(milk), orders: List<TripOrder> = listOf(order)) = TripDetail(
        id = 7, number = "TRP-000007", status = "out", canSell = true, float = 100_000.0, lines = lines, orders = orders,
    )

    private fun saleOf(cartons: Int, orderId: Int? = null, id: String = "a") = TripSaleRequest(
        requestId = id,
        customerId = 1,
        orderId = orderId,
        payment = "cash",
        lines = listOf(TripSaleLineQty(productId = 1, cartons = cartons, pricePerCarton = if (orderId == null) 25_000 else null)),
    )

    // A sale at a stop may take only what is for sale, not the whole van.
    @Test
    fun aSaleAtAStopCanOnlyTakeWhatIsForSale() {
        assertEquals(24, milk.sellable)
        // Two cartons of 12 is 24, exactly what is free.
        assertFalse(SaleLine(milk, onVehicle = milk.sellable, cartons = 2, price = 25_000).tooMuch)
        // Three cartons would eat into the order's goods, and the phone refuses before the cash is taken.
        assertTrue(SaleLine(milk, onVehicle = milk.sellable, cartons = 3, price = 25_000).tooMuch)
    }

    // A pack saved by an older app knows nothing of orders, so the whole van is still for sale.
    @Test
    fun aPackWithoutOrdersStillSellsTheWholeVan() {
        val old = TripLine(productId = 1, piecesPerCarton = 12, loaded = 120, sold = 48, onVehicle = 72)
        assertEquals(72, old.sellable)
    }

    // Delivering an order: all that the van carries for it is offered, and no more.
    @Test
    fun anOrderIsDeliveredUpToWhatTheVanCarriesForIt() {
        val line = SaleLine(milk, onVehicle = orderLine.toDeliver, cartons = 4, price = 25_000, fixedPrice = true)
        assertFalse(line.tooMuch)
        // Less is fine: the rest stays on the order for another day.
        assertFalse(line.copy(cartons = 1).tooMuch)
        assertFalse(line.copy(cartons = 0).tooMuch)
        // More than is on the van for the order is not.
        assertTrue(line.copy(cartons = 5).tooMuch)
    }

    // An order delivery saved with no signal comes off that order and off what is held for the
    // orders, and leaves what is for sale alone.
    @Test
    fun anOrderDeliveryWaitingOnThePhoneComesOffTheOrder() {
        val store = TripOutboxStore(Memory())
        val outbox = store.addSale(7, saleOf(cartons = 2, orderId = 31))
        val state = TripDetailState(loading = false, trip = trip(), outbox = outbox)

        // 24 pieces handed over: the order has 24 left and the van holds 24 for it.
        assertEquals(24, state.toDeliver(order)[1])
        assertEquals(24, state.forOrders[1])
        // What is for sale at a stop has not changed at all.
        assertEquals(24, state.forSale[1])
        // The van itself is 24 lighter.
        assertEquals(48, state.onVehicle[1])
        assertTrue(state.waitingOn(order))
    }

    // A sale at a stop comes off what is for sale, never off an order.
    @Test
    fun aSaleAtAStopWaitingOnThePhoneLeavesTheOrdersAlone() {
        val store = TripOutboxStore(Memory())
        val outbox = store.addSale(7, saleOf(cartons = 1))
        val state = TripDetailState(loading = false, trip = trip(), outbox = outbox)

        assertEquals(12, state.forSale[1])
        assertEquals(48, state.forOrders[1])
        assertEquals(48, state.toDeliver(order)[1])
        assertFalse(state.waitingOn(order))
    }

    // The same sums on the pack kept on the phone, once the delivery has reached the server.
    @Test
    fun anOnlineOrderDeliveryComesOffThePackToo() {
        val store = TripOutboxStore(Memory())
        store.savePack(TripPack(trip = trip()))

        store.takeOffPack(7, saleOf(cartons = 2, orderId = 31).lines, orderId = 31)

        val pack = store.loadPack(7)!!
        val line = pack.trip.lines.first()
        assertEquals(48, line.onVehicle)
        assertEquals(24, line.forOrders)
        assertEquals(2, line.forOrdersQty.cartons)
        // Nothing of what was free to sell was touched.
        assertEquals(24, line.forSale)
        val left = pack.trip.orders.first().lines.first()
        assertEquals(24, left.toDeliver)
        assertEquals(2, left.toDeliverQty.cartons)
        assertEquals(24, left.delivered)
        assertEquals(24, left.onVehicle)
    }

    @Test
    fun anOnlineSaleAtAStopComesOffWhatIsForSale() {
        val store = TripOutboxStore(Memory())
        store.savePack(TripPack(trip = trip()))

        store.takeOffPack(7, saleOf(cartons = 1).lines)

        val pack = store.loadPack(7)!!
        val line = pack.trip.lines.first()
        assertEquals(60, line.onVehicle)
        assertEquals(12, line.forSale)
        // The order still has everything it came for.
        assertEquals(48, line.forOrders)
        assertEquals(48, pack.trip.orders.first().lines.first().toDeliver)
    }

    // Two deliveries of the same order, one waiting on the phone, add up.
    @Test
    fun twoWaitingDeliveriesOfOneOrderBothCountOff() {
        val outbox = TripOutbox(
            tripId = 7,
            sales = listOf(
                OutboxSale(saleOf(cartons = 1, orderId = 31, id = "a")),
                OutboxSale(saleOf(cartons = 2, orderId = 31, id = "b")),
            ),
        )
        val state = TripDetailState(loading = false, trip = trip(), outbox = outbox)
        assertEquals(12, state.toDeliver(order)[1])
        assertEquals(12, state.forOrders[1])
    }

    // Stands in for the phone's files.
    private class Memory : Files {
        private val files = mutableMapOf<String, String>()

        override fun read(name: String): String? = files[name]

        override fun write(name: String, text: String) {
            files[name] = text
        }

        override fun delete(name: String) {
            files.remove(name)
        }
    }
}
