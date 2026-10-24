package com.salesapp.data

// Where the trip screens get their data. A sale or expense is tried on the server first; if that
// fails for any reason other than the server refusing it, it goes to the outbox on the phone and is
// sent later. Nothing is lost either way.
class TripsRepository(
    private val api: Api,
    private val outbox: TripOutboxStore,
    private val app: AppState,
) {
    suspend fun trips(status: String?, kind: String?): List<TripListItem> = api.trips(status, kind)

    suspend fun trip(id: Int): TripDetail = api.trip(id)

    // The pack is kept on the phone, so the trip can be opened and sold from with no network.
    suspend fun pack(id: Int): TripPack = api.tripPack(id).also { outbox.savePack(it) }

    fun savedPack(id: Int): TripPack? = outbox.loadPack(id)

    fun outboxOf(id: Int): TripOutbox = outbox.load(id)

    suspend fun plan(request: PlanTripRequest): TripDetail = api.planTrip(request)

    suspend fun change(id: Int, request: PlanTripRequest): TripDetail = api.changeTrip(id, request)

    suspend fun delete(id: Int) = api.deleteTrip(id)

    suspend fun load(id: Int, request: LoadTripRequest): TripDetail = api.loadTrip(id, request)

    suspend fun close(id: Int, request: CloseTripRequest): TripDetail = api.closeTrip(id, request)

    suspend fun vehicles(facilityCode: String): List<Vehicle> =
        api.vehicles().filter { it.active && (it.facilityCode == null || it.facilityCode == facilityCode) }

    suspend fun drivers(): List<Driver> = api.drivers()

    // Salesmen and sales managers of this warehouse's companies: whoever can lead a trip.
    suspend fun possibleLeaders(companies: List<String>): List<UserRow> =
        api.users().filter { u ->
            u.active && (u.role == "salesman" || u.role == "sales_manager") && u.companies.any { it in companies }
        }

    suspend fun products(): List<Product> = api.products(null, null, false)

    suspend fun myFacilities(): List<Company> {
        val user = app.user ?: return emptyList()
        val seesAll = Role.fromCode(user.role)?.seesAllCompanies == true
        return api.companies().filter { seesAll || it.code in user.companies }
    }

    // A sale: straight to the server when there is a network, otherwise into the outbox. A refusal
    // from the server (not_on_vehicle and the like) is not kept: it would only fail again.
    suspend fun sell(tripId: Int, request: TripSaleRequest): SaleOutcome =
        try {
            SaleOutcome.Sent(api.tripSale(tripId, request))
        } catch (e: ApiException) {
            if (e.keepForLater) SaleOutcome.Saved(outbox.addSale(tripId, request)) else throw e
        }

    suspend fun spend(tripId: Int, request: TripExpenseRequest): ExpenseOutcome =
        try {
            ExpenseOutcome.Sent(api.tripExpense(tripId, request))
        } catch (e: ApiException) {
            if (e.keepForLater) ExpenseOutcome.Saved(outbox.addExpense(tripId, request)) else throw e
        }

    // Sends everything the phone is holding for this trip and keeps only what the server refused.
    suspend fun sync(tripId: Int): SyncOutcome {
        val waiting = outbox.load(tripId)
        if (waiting.isEmpty) return SyncOutcome(waiting, null, emptyList())
        val result = api.syncTrip(
            tripId,
            SyncRequest(sales = waiting.sales.map { it.request }, expenses = waiting.expenses.map { it.request }),
        )
        val left = outbox.applySync(tripId, result)
        val refused = (result.sales + result.expenses).filterNot { it.ok }.mapNotNull { it.message ?: it.error }
        return SyncOutcome(left, result.trip, refused)
    }

    fun dropSale(tripId: Int, requestId: String): TripOutbox = outbox.removeSale(tripId, requestId)

    fun dropExpense(tripId: Int, requestId: String): TripOutbox = outbox.removeExpense(tripId, requestId)

    // Once a trip is closed there is nothing left to keep on the phone.
    fun forget(tripId: Int) {
        outbox.clear(tripId)
        outbox.clearPack(tripId)
    }
}

// A sale either reached the server or is waiting on the phone.
sealed interface SaleOutcome {
    data class Sent(val sale: TripSaleDetail) : SaleOutcome

    data class Saved(val outbox: TripOutbox) : SaleOutcome
}

sealed interface ExpenseOutcome {
    data class Sent(val expense: TripExpense) : ExpenseOutcome

    data class Saved(val outbox: TripOutbox) : ExpenseOutcome
}

data class SyncOutcome(val outbox: TripOutbox, val trip: TripDetail?, val refused: List<String>)

// Worth keeping on the phone and trying again: no network, or the server could not answer. A
// refusal the server gave on purpose would only be refused again.
val ApiException.keepForLater: Boolean
    get() = code == "offline" || code == "bad_reply" || status >= 500 || status == 0
