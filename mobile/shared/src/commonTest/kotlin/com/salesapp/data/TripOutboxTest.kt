package com.salesapp.data

import com.salesapp.core.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// The outbox is the whole point of Phase 9: a sale made with no signal has to survive the app being
// closed, show on screen at once, and reach the server exactly once.
class TripOutboxTest {
    // Stands in for the phone's files, so the test does not touch the real ones.
    private class Memory : Files {
        val files = mutableMapOf<String, String>()
        var writes = 0
            private set

        override fun read(name: String): String? = files[name]

        override fun write(name: String, text: String) {
            writes++
            files[name] = text
        }

        override fun delete(name: String) {
            files.remove(name)
        }
    }

    private fun sale(id: String, productId: Int = 1, cartons: Int = 2) = TripSaleRequest(
        requestId = id,
        customerId = 1,
        stop = "Salima market",
        payment = "cash",
        receivedBy = "Mrs Phiri",
        signature = "iVBORw0KGgo=",
        soldAt = "2026-10-25T09:00:00.000Z",
        lines = listOf(TripSaleLineQty(productId = productId, cartons = cartons, pricePerCarton = 25_000)),
    )

    private fun expense(id: String) = TripExpenseRequest(requestId = id, amount = 30_000, description = "Fuel")

    @Test
    fun aSaleSavedOfflineSurvivesTheAppBeingClosed() {
        val files = Memory()
        TripOutboxStore(files).addSale(7, sale("a"))

        // A new store, as if the app had been closed and opened again.
        val outbox = TripOutboxStore(files).load(7)
        assertEquals(1, outbox.sales.size)
        assertEquals("a", outbox.sales[0].requestId)
        assertEquals("Mrs Phiri", outbox.sales[0].request.receivedBy)
        // The signature goes with it, so nothing has to be signed twice.
        assertEquals("iVBORw0KGgo=", outbox.sales[0].request.signature)
    }

    @Test
    fun theSameSaleTwiceIsStillOneSale() {
        val store = TripOutboxStore(Memory())
        store.addSale(7, sale("a"))
        val outbox = store.addSale(7, sale("a", cartons = 3))
        assertEquals(1, outbox.sales.size)
        // The retry's own numbers win, because it is the same sale corrected.
        assertEquals(3, outbox.sales[0].request.lines[0].cartons)
    }

    // What the phone has sold but not sent comes off the van on screen at once.
    @Test
    fun whatIsWaitingComesOffWhatIsOnTheVan() {
        val store = TripOutboxStore(Memory())
        store.addSale(7, sale("a", productId = 1, cartons = 2))
        val outbox = store.addSale(7, sale("b", productId = 1, cartons = 1))
        assertEquals(mapOf(1 to 36), outbox.soldPieces(mapOf(1 to 12)))
    }

    @Test
    fun syncTakesOffWhatTheServerAccepted() {
        val store = TripOutboxStore(Memory())
        store.addSale(7, sale("a"))
        store.addSale(7, sale("b"))
        store.addExpense(7, expense("e1"))

        val left = store.applySync(
            7,
            SyncResult(
                sales = listOf(
                    SyncItemResult(requestId = "a", ok = true),
                    // A repeat counts as ok: the server already had it.
                    SyncItemResult(requestId = "b", ok = true, repeat = true),
                ),
                expenses = listOf(SyncItemResult(requestId = "e1", ok = true, repeat = true)),
            ),
        )
        assertTrue(left.isEmpty)
        // Nothing is left on the phone, so the file is gone too.
        assertNull(TripOutboxStore(Memory()).load(7).sales.firstOrNull())
    }

    @Test
    fun oneTheServerRefusedStaysWithItsReason() {
        val store = TripOutboxStore(Memory())
        store.addSale(7, sale("a"))
        store.addSale(7, sale("b"))

        val left = store.applySync(
            7,
            SyncResult(
                sales = listOf(
                    SyncItemResult(requestId = "a", ok = true),
                    SyncItemResult(requestId = "b", ok = false, error = "not_on_vehicle", message = "Only 2 ctn is on the van."),
                ),
            ),
        )
        assertEquals(1, left.sales.size)
        assertEquals("b", left.sales[0].requestId)
        // The server's own words, so the leader knows what to fix.
        assertEquals("Only 2 ctn is on the van.", left.sales[0].failed)
    }

    // An item the sync did not answer for is kept: it was never sent.
    @Test
    fun anItemWithNoAnswerIsKept() {
        val store = TripOutboxStore(Memory())
        store.addSale(7, sale("a"))
        store.addSale(7, sale("b"))
        val left = store.applySync(7, SyncResult(sales = listOf(SyncItemResult(requestId = "a", ok = true))))
        assertEquals(listOf("b"), left.sales.map { it.requestId })
    }

    @Test
    fun theLeaderCanGiveUpOnOneTheServerKeepsRefusing() {
        val store = TripOutboxStore(Memory())
        store.addSale(7, sale("a"))
        store.addExpense(7, expense("e1"))
        assertEquals(2, store.load(7).count)

        store.removeSale(7, "a")
        val left = store.removeExpense(7, "e1")
        assertTrue(left.isEmpty)
    }

    @Test
    fun oneTripsOutboxIsNotAnothers() {
        val store = TripOutboxStore(Memory())
        store.addSale(7, sale("a"))
        store.addSale(8, sale("b"))
        assertEquals(listOf("a"), store.load(7).sales.map { it.requestId })
        assertEquals(listOf("b"), store.load(8).sales.map { it.requestId })
    }

    // Closing is only allowed once the outbox for that trip is empty.
    @Test
    fun closingWaitsForTheOutboxToEmpty() {
        val store = TripOutboxStore(Memory())
        assertTrue(store.load(7).isEmpty)
        store.addSale(7, sale("a"))
        assertFalse(store.load(7).isEmpty)
        assertEquals(1, store.load(7).count)
        store.applySync(7, SyncResult(sales = listOf(SyncItemResult(requestId = "a", ok = true))))
        assertTrue(store.load(7).isEmpty)
    }

    // A file the phone cannot read holds sales already paid for in cash, so it is put aside and
    // never written over, and the trip screen says the office has to be told.
    @Test
    fun anUnreadableFileIsKeptAsideAndWarnedAbout() {
        val files = Memory()
        files.write("trip-7-outbox.json", "{ not json")
        val store = TripOutboxStore(files)

        val outbox = store.load(7)
        assertTrue(outbox.isEmpty)
        assertTrue(store.hasUnreadable(7))

        // The text is kept under its own name, and the original is out of the way.
        val kept = files.files.keys.filter { it.startsWith("trip-7-outbox-unreadable-") && it.endsWith(".json") }
        assertEquals(1, kept.size)
        assertEquals("{ not json", files.files[kept[0]])
        assertNull(files.files["trip-7-outbox.json"])
    }

    // The next sale must not write over what could not be read.
    @Test
    fun aSaleAfterAnUnreadableFileDoesNotOverwriteIt() {
        val files = Memory()
        files.write("trip-7-outbox.json", "{ not json")
        val store = TripOutboxStore(files)
        store.load(7)
        val kept = files.files.keys.first { it.startsWith("trip-7-outbox-unreadable-") }

        store.addSale(7, sale("a"))

        // The put-aside copy is untouched, and the new sale is in a file of its own.
        assertEquals("{ not json", files.files[kept])
        assertEquals(listOf("a"), store.load(7).sales.map { it.requestId })
        assertTrue(store.hasUnreadable(7))
    }

    // Another trip is not tarred with it.
    @Test
    fun theWarningIsOnlyForTheTripItHappenedTo() {
        val files = Memory()
        files.write("trip-7-outbox.json", "{ not json")
        val store = TripOutboxStore(files)
        store.load(7)
        assertTrue(store.hasUnreadable(7))
        assertFalse(store.hasUnreadable(8))
    }

    // The pack is kept too, so a trip on the road opens with no network at all.
    @Test
    fun thePackIsKeptOnThePhone() {
        val files = Memory()
        val store = TripOutboxStore(files)
        val pack = TripPack(
            trip = TripDetail(id = 7, number = "TRP-000007", route = "Salima", status = "out", canSell = true),
            customers = listOf(PackCustomer(id = 1, name = "Salima Shop", cashOnly = true)),
            expenseKinds = listOf("Fuel", "Other"),
        )
        store.savePack(pack)

        val saved = TripOutboxStore(files).loadPack(7)
        assertEquals("TRP-000007", saved?.trip?.number)
        assertEquals(listOf("Fuel", "Other"), saved?.expenseKinds)
        assertEquals(true, saved?.customers?.firstOrNull()?.cashOnly)
    }

    @Test
    fun aClosedTripLeavesNothingOnThePhone() {
        val files = Memory()
        val store = TripOutboxStore(files)
        store.addSale(7, sale("a"))
        store.savePack(TripPack(trip = TripDetail(id = 7, number = "TRP-000007")))
        store.clear(7)
        store.clearPack(7)
        assertTrue(files.files.isEmpty())
    }

    // What a sale took comes off the pack kept on the phone, so the van count stays right between
    // downloads. Without this the next form would offer goods that have already gone.
    @Test
    fun anOnlineSaleComesOffThePackOnThePhone() {
        val files = Memory()
        val store = TripOutboxStore(files)
        store.savePack(
            TripPack(
                trip = TripDetail(
                    id = 7,
                    number = "TRP-000007",
                    status = "out",
                    canSell = true,
                    lines = listOf(
                        TripLine(productId = 1, brand = "Lucky", name = "Milk", packSize = "500 ml", piecesPerCarton = 12, loaded = 120, sold = 48, onVehicle = 72),
                    ),
                ),
            ),
        )

        // Two cartons of twelve went out.
        store.takeOffPack(7, listOf(TripSaleLineQty(productId = 1, cartons = 2, pricePerCarton = 25_000)))

        val line = TripOutboxStore(files).loadPack(7)?.trip?.lines?.first()
        assertEquals(48, line?.onVehicle)
        assertEquals(4, line?.onVehicleQty?.cartons)
        assertEquals(72, line?.sold)
    }

    @Test
    fun takingOffMoreThanIsThereStopsAtNothing() {
        val files = Memory()
        val store = TripOutboxStore(files)
        store.savePack(
            TripPack(
                trip = TripDetail(
                    id = 7,
                    lines = listOf(TripLine(productId = 1, piecesPerCarton = 12, loaded = 24, sold = 0, onVehicle = 24)),
                ),
            ),
        )
        store.takeOffPack(7, listOf(TripSaleLineQty(productId = 1, cartons = 5, pricePerCarton = 1)))
        assertEquals(0, store.loadPack(7)?.trip?.lines?.first()?.onVehicle)
    }
}
