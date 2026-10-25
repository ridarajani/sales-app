package com.salesapp.ui.trips

import com.salesapp.core.KeyValueStore
import com.salesapp.data.AppState
import com.salesapp.data.LoadTripRequest
import com.salesapp.data.SessionStore
import com.salesapp.data.SyncOutcome
import com.salesapp.data.TripDetail
import com.salesapp.data.TripOutbox
import com.salesapp.data.TripOutboxProblem
import com.salesapp.data.TripPack
import com.salesapp.data.TripSource
import com.salesapp.data.User
import com.salesapp.data.Vehicle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain

// Sending the sales file to the office is what makes the trip un-closable, so the screen has to be
// drawn from the trip as it is after the send, not before it.
@OptIn(ExperimentalCoroutinesApi::class)
class TripDetailProblemTest {
    private val trip = TripDetail(
        id = 7,
        number = "TRP-000007",
        status = "out",
        route = "Salima and Nkhotakota",
        float = 100_000.0,
        canSell = true,
        canClose = true,
    )

    // Stands in for the server and the phone's files: the first file goes up on the first load, and
    // only then does the trip have an open problem.
    private class Server(private val trip: TripDetail) : TripSource {
        var sends = 0
            private set
        var fetches = 0
            private set
        var forgotten: List<TripOutboxProblem>? = null
            private set
        var kept = true

        override suspend fun trip(id: Int): TripDetail {
            fetches++
            // The office knows of the file only once it has been sent.
            return if (sends == 0) {
                trip
            } else {
                trip.copy(
                    outboxProblems = listOf(TripOutboxProblem(id = 1, requestId = "trip-7-outbox-unreadable-1.json")),
                    openOutboxProblems = 1,
                )
            }
        }

        override suspend fun pack(id: Int): TripPack = TripPack(trip = trip)

        override fun savedPack(id: Int): TripPack? = null

        override fun outboxOf(id: Int): TripOutbox = TripOutbox(id)

        override fun hasUnreadable(id: Int): Boolean = kept

        override suspend fun sendOutboxProblems(tripId: Int): Int {
            sends++
            return if (sends == 1) 1 else 0
        }

        override fun forgetClearedProblems(tripId: Int, problems: List<TripOutboxProblem>) {
            forgotten = problems
        }

        override suspend fun vehicles(facilityCode: String): List<Vehicle> = emptyList()

        override suspend fun load(id: Int, request: LoadTripRequest): TripDetail = trip

        override suspend fun sync(tripId: Int): SyncOutcome = SyncOutcome(TripOutbox(tripId), null, emptyList())

        override fun dropSale(tripId: Int, requestId: String): TripOutbox = TripOutbox(tripId)

        override fun dropExpense(tripId: Int, requestId: String): TripOutbox = TripOutbox(tripId)

        override fun forget(tripId: Int) = Unit
    }

    private fun appState() = AppState(KeyValueStore(), SessionStore(KeyValueStore())).apply {
        user = User(id = 4, name = "Chimwemwe Banda", phone = "0991234567", role = "salesman", companies = listOf("LV"))
    }

    // The bug this guards: the trip was read before the file went up, so on that one load the
    // screen still offered Close trip for a trip the server would refuse to close.
    @Test
    fun theTripIsReadAgainAfterAFileGoesUp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val server = Server(trip)
            val model = TripDetailViewModel(7, server, appState())
            advanceUntilIdle()

            val state = model.state.value
            assertEquals(1, server.sends)
            // Once before the send and once after it.
            assertEquals(2, server.fetches)
            assertTrue(state.outboxProblemOpen)
            assertFalse(state.canClose)
            assertEquals(1, state.trip?.openOutboxProblems)
            // The files are only let go of against the fresh list, which says it is not cleared.
            assertEquals(listOf(TripOutboxProblem(id = 1, requestId = "trip-7-outbox-unreadable-1.json")), server.forgotten)
        } finally {
            Dispatchers.resetMain()
        }
    }

    // With nothing kept aside there is no second call: the trip is read once, as before.
    @Test
    fun aTripWithNothingKeptAsideIsReadOnce() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        try {
            val server = Server(trip).apply { kept = false }
            val model = TripDetailViewModel(7, server, appState())
            advanceUntilIdle()

            assertEquals(0, server.sends)
            assertEquals(1, server.fetches)
            assertFalse(model.state.value.outboxProblemOpen)
            assertTrue(model.state.value.canClose)
        } finally {
            Dispatchers.resetMain()
        }
    }
}
