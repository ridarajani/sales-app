package com.salesapp.data

import com.salesapp.core.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

// A sales file this phone could not read holds cash somebody has already paid. It goes to the office
// once, is never deleted to record that it went, and only goes away once the office has cleared it.
class OutboxProblemsTest {
    // Stands in for the phone's files.
    private class Memory : Files {
        val files = mutableMapOf<String, String>()

        override fun read(name: String): String? = files[name]

        override fun write(name: String, text: String) {
            files[name] = text
        }

        override fun delete(name: String) {
            files.remove(name)
        }
    }

    private fun sale(id: String) = TripSaleRequest(
        requestId = id,
        customerId = 1,
        payment = "cash",
        receivedBy = "Mrs Phiri",
        signature = "iVBORw0KGgo=",
        lines = listOf(TripSaleLineQty(productId = 1, cartons = 2, pricePerCarton = 25_000)),
    )

    // Writes an outbox file that cannot be read, then reads it, which puts it aside.
    private fun keepOneAside(store: TripOutboxStore, files: Memory, tripId: Int, text: String): String {
        val before = store.keptFiles(tripId).toSet()
        files.write("trip-$tripId-outbox.json", text)
        store.load(tripId)
        return store.keptFiles(tripId).first { it !in before }
    }

    @Test
    fun eachKeptFileGoesUpOnceAndIsNeverDeletedToRecordIt() = runTest {
        val files = Memory()
        val store = TripOutboxStore(files)
        store.addSale(7, sale("a"))
        val first = keepOneAside(store, files, 7, "{ this is not json")
        val second = keepOneAside(store, files, 7, "{ nor is this")
        assertEquals(listOf(first, second), store.keptFiles(7))
        assertTrue(store.hasUnreadable(7))

        val sent = mutableListOf<String>()
        store.sendKept(7) { name, text ->
            sent += name
            // The file goes up as it was written, so the office can read it by hand.
            assertTrue(text.isNotEmpty())
        }
        assertEquals(listOf(first, second), sent)

        // Asked again, there is nothing left to send, and both files are still on the phone.
        store.sendKept(7) { name, _ -> sent += name }
        assertEquals(listOf(first, second), sent)
        assertEquals(emptyList(), store.unsentKept(7))
        assertNotNull(store.keptText(first))
        assertNotNull(store.keptText(second))
        assertTrue(store.hasUnreadable(7))
    }

    // A send that fails leaves the file to go up next time.
    @Test
    fun aFileThatCouldNotBeSentIsSentAgainLater() = runTest {
        val files = Memory()
        val store = TripOutboxStore(files)
        val kept = keepOneAside(store, files, 7, "{ broken")

        val tries = mutableListOf<String>()
        try {
            store.sendKept(7) { name, _ ->
                tries += name
                throw IllegalStateException("no network")
            }
        } catch (e: IllegalStateException) {
            // The trip screen swallows this and tries again when it next loads.
        }
        assertEquals(listOf(kept), tries)
        assertEquals(listOf(kept), store.unsentKept(7))

        store.sendKept(7) { name, _ -> tries += name }
        assertEquals(listOf(kept, kept), tries)
        assertEquals(emptyList(), store.unsentKept(7))
    }

    @Test
    fun theFilesGoOnlyOnceTheOfficeHasClearedEveryOne() = runTest {
        val files = Memory()
        val store = TripOutboxStore(files)
        val first = keepOneAside(store, files, 7, "{ broken")
        val second = keepOneAside(store, files, 7, "{ also broken")
        store.sendKept(7) { _, _ -> }

        // One cleared, one still open: nothing goes.
        assertFalse(store.forgetIfAllCleared(7, setOf(first)))
        assertTrue(store.hasUnreadable(7))
        assertNotNull(store.keptText(first))

        // Both cleared: the files and the notes beside them go.
        assertTrue(store.forgetIfAllCleared(7, setOf(first, second)))
        assertFalse(store.hasUnreadable(7))
        assertEquals(emptyList(), store.keptFiles(7))
        assertNull(store.keptText(first))
        assertNull(store.keptText(second))
        assertEquals(emptyList(), store.unsentKept(7))

        // With nothing kept, there is nothing to forget.
        assertFalse(store.forgetIfAllCleared(7, setOf(first)))
    }

    // The warning and the list of files survive the app being closed.
    @Test
    fun theKeptFilesSurviveTheAppBeingClosed() = runTest {
        val files = Memory()
        val kept = keepOneAside(TripOutboxStore(files), files, 7, "{ broken")

        val later = TripOutboxStore(files)
        assertTrue(later.hasUnreadable(7))
        assertEquals(listOf(kept), later.keptFiles(7))
        assertEquals(listOf(kept), later.unsentKept(7))
        later.markSent(7, kept)

        val laterStill = TripOutboxStore(files)
        assertEquals(emptyList(), laterStill.unsentKept(7))
        assertTrue(laterStill.hasUnreadable(7))
    }

    // One trip's files have nothing to do with another's.
    @Test
    fun anotherTripsFilesAreLeftAlone() = runTest {
        val files = Memory()
        val store = TripOutboxStore(files)
        val mine = keepOneAside(store, files, 7, "{ broken")
        val other = keepOneAside(store, files, 8, "{ broken")

        store.sendKept(7) { _, _ -> }
        assertEquals(emptyList(), store.unsentKept(7))
        assertEquals(listOf(other), store.unsentKept(8))

        store.forgetIfAllCleared(7, setOf(mine))
        assertFalse(store.hasUnreadable(7))
        assertTrue(store.hasUnreadable(8))
        assertNotNull(store.keptText(other))
    }
}
