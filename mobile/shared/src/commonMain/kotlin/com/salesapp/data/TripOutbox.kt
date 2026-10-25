package com.salesapp.data

import com.salesapp.core.Files
import com.salesapp.core.nowUtcTimestamp
import com.salesapp.core.splitPieces
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

// What the phone is still holding for one trip, waiting for the network. A sale or expense made
// with no signal is written here first, so it survives the app being closed, and the on-vehicle
// numbers on screen go down at once. Nothing is thrown away until the server has taken it.

// One sale waiting to go up. `failed` carries the server's own words from the last try.
@Serializable
data class OutboxSale(val request: TripSaleRequest, val failed: String? = null) {
    val requestId: String get() = request.requestId
}

@Serializable
data class OutboxExpense(val request: TripExpenseRequest, val failed: String? = null) {
    val requestId: String get() = request.requestId
}

@Serializable
data class TripOutbox(
    val tripId: Int = 0,
    val sales: List<OutboxSale> = emptyList(),
    val expenses: List<OutboxExpense> = emptyList(),
) {
    val count: Int get() = sales.size + expenses.size

    val isEmpty: Boolean get() = count == 0

    val isNotEmpty: Boolean get() = count > 0

    // How much of each product the phone has already sold but not sent, so the screen can take it
    // off what is on the vehicle.
    fun soldPieces(perCarton: Map<Int, Int>): Map<Int, Int> {
        val out = mutableMapOf<Int, Int>()
        for (sale in sales) {
            for (line in sale.request.lines) {
                val size = (perCarton[line.productId] ?: 1).coerceAtLeast(1)
                out[line.productId] = (out[line.productId] ?: 0) + line.cartons * size + line.pieces
            }
        }
        return out
    }
}

// The outbox on the phone, one file per trip. Also keeps the downloaded pack, so a trip that is on
// the road can be opened and sold from with no network at all.
class TripOutboxStore(private val files: Files) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private fun outboxName(tripId: Int) = "trip-$tripId-outbox.json"

    private fun packName(tripId: Int) = "trip-$tripId-pack.json"

    // Written when an outbox file could not be read, so the warning survives a restart.
    private fun markerName(tripId: Int) = "trip-$tripId-outbox-unreadable.marker"

    fun load(tripId: Int): TripOutbox {
        val text = files.read(outboxName(tripId)) ?: return TripOutbox(tripId)
        return try {
            json.decodeFromString<TripOutbox>(text)
        } catch (e: Exception) {
            // These are sales the customer has already paid for in cash, so the file is never
            // thrown away or written over: it is put aside under its own name for the office.
            keepAside(tripId, text)
            TripOutbox(tripId)
        }
    }

    // Moves an unreadable outbox out of the way and leaves a marker, so the next save cannot
    // overwrite it and the trip screen can say something about it.
    private fun keepAside(tripId: Int, text: String) {
        val stamp = nowUtcTimestamp().filter { it.isLetterOrDigit() }
        val kept = "trip-$tripId-outbox-unreadable-$stamp.json"
        files.write(kept, text)
        // Only once the copy is safely written is the original let go.
        files.delete(outboxName(tripId))
        files.write(markerName(tripId), listOfNotNull(files.read(markerName(tripId)), kept).joinToString("\n"))
    }

    // True when something saved on this phone could not be read and is waiting for the office.
    fun hasUnreadable(tripId: Int): Boolean = files.read(markerName(tripId)) != null

    private fun save(outbox: TripOutbox) {
        if (outbox.isEmpty) files.delete(outboxName(outbox.tripId)) else files.write(outboxName(outbox.tripId), json.encodeToString(outbox))
    }

    // Adds a sale, or replaces the one with the same requestId: a retry is the same sale.
    fun addSale(tripId: Int, request: TripSaleRequest): TripOutbox {
        val outbox = load(tripId)
        val next = outbox.copy(
            tripId = tripId,
            sales = outbox.sales.filterNot { it.requestId == request.requestId } + OutboxSale(request),
        )
        save(next)
        return next
    }

    fun addExpense(tripId: Int, request: TripExpenseRequest): TripOutbox {
        val outbox = load(tripId)
        val next = outbox.copy(
            tripId = tripId,
            expenses = outbox.expenses.filterNot { it.requestId == request.requestId } + OutboxExpense(request),
        )
        save(next)
        return next
    }

    // The leader gave up on one the server keeps refusing.
    fun removeSale(tripId: Int, requestId: String): TripOutbox {
        val next = load(tripId).let { it.copy(tripId = tripId, sales = it.sales.filterNot { s -> s.requestId == requestId }) }
        save(next)
        return next
    }

    fun removeExpense(tripId: Int, requestId: String): TripOutbox {
        val next = load(tripId).let { it.copy(tripId = tripId, expenses = it.expenses.filterNot { e -> e.requestId == requestId }) }
        save(next)
        return next
    }

    // What the sync answered. An item the server took, or already had (a repeat), leaves the
    // outbox; one it refused stays, with its reason, so the leader can fix or drop it.
    fun applySync(tripId: Int, result: SyncResult): TripOutbox {
        val outbox = load(tripId)
        val sales = outbox.sales.mapNotNull { item ->
            val answer = result.sales.firstOrNull { it.requestId == item.requestId } ?: return@mapNotNull item
            if (answer.ok) null else item.copy(failed = answer.message ?: answer.error)
        }
        val expenses = outbox.expenses.mapNotNull { item ->
            val answer = result.expenses.firstOrNull { it.requestId == item.requestId } ?: return@mapNotNull item
            if (answer.ok) null else item.copy(failed = answer.message ?: answer.error)
        }
        val next = outbox.copy(tripId = tripId, sales = sales, expenses = expenses)
        save(next)
        return next
    }

    fun clear(tripId: Int) {
        files.delete(outboxName(tripId))
    }

    // Takes what one sale carried off the pack kept on the phone, so the van count stays right
    // between downloads. Called once the server has taken the sale.
    fun takeOffPack(tripId: Int, lines: List<TripSaleLineQty>) {
        val pack = loadPack(tripId) ?: return
        val perCarton = pack.trip.lines.associate { it.productId to it.piecesPerCarton.coerceAtLeast(1) }
        val sold = lines.associate { line ->
            line.productId to (line.cartons * (perCarton[line.productId] ?: 1) + line.pieces)
        }
        val next = pack.trip.lines.map { line ->
            val off = sold[line.productId] ?: 0
            if (off == 0) {
                line
            } else {
                val left = (line.onVehicle - off).coerceAtLeast(0)
                val soldNow = line.sold + off
                line.copy(
                    sold = soldNow,
                    soldQty = split(soldNow, line.piecesPerCarton),
                    onVehicle = left,
                    onVehicleQty = split(left, line.piecesPerCarton),
                )
            }
        }
        savePack(pack.copy(trip = pack.trip.copy(lines = next)))
    }

    private fun split(pieces: Int, piecesPerCarton: Int): PieceQty {
        val (cartons, loose) = splitPieces(pieces, piecesPerCarton)
        return PieceQty(pieces = pieces, cartons = cartons, loosePieces = loose)
    }

    // The pack is kept too, so the trip opens and sells with no network.
    fun savePack(pack: TripPack) {
        files.write(packName(pack.trip.id), json.encodeToString(pack))
    }

    fun loadPack(tripId: Int): TripPack? {
        val text = files.read(packName(tripId)) ?: return null
        return try {
            json.decodeFromString<TripPack>(text)
        } catch (e: Exception) {
            null
        }
    }

    fun clearPack(tripId: Int) {
        files.delete(packName(tripId))
    }
}
