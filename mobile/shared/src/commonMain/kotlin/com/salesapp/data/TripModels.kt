package com.salesapp.data

import kotlinx.serialization.Serializable

// Phase 9: minibus routes and upcountry trips. The leader sells at each stop, often with no
// network, so every sale and expense carries its own requestId and the sale its own soldAt time.
// The warehouse loads and sees no money, so every money field here can be missing.

// kind: minibus or upcountry. status: planned, out or closed.
@Serializable
data class TripListItem(
    val id: Int,
    val number: String = "",
    val kind: String = "upcountry",
    val facilityCode: String = "",
    val tripDate: String = "",
    val route: String = "",
    val status: String = "planned",
    val vehiclePlate: String? = null,
    val leaderName: String = "",
    val staff: String = "",
    val cartons: Int = 0,
    val loosePieces: Int = 0,
    val soldCartons: Int = 0,
    // Missing for the warehouse.
    val salesTotal: Double? = null,
    val salesCount: Int = 0,
) {
    val isMinibus: Boolean get() = kind == "minibus"

    val waitingToLoad: Boolean get() = status == "planned"

    val onTheRoad: Boolean get() = status == "out"

    val closed: Boolean get() = status == "closed"
}

@Serializable
data class PieceQty(val pieces: Int = 0, val cartons: Int = 0, val loosePieces: Int = 0)

@Serializable
data class TripBatch(
    val batchId: Int,
    val batchNo: String = "",
    val expiryDate: String = "",
    val pieces: Int = 0,
    val sold: Int = 0,
)

// planned, loaded and sold are pieces; the *Qty fields are the same split into cartons.
@Serializable
data class TripLine(
    val productId: Int,
    val code: String = "",
    val brand: String = "",
    val name: String = "",
    val packSize: String = "",
    val companyCode: String = "",
    val piecesPerCarton: Int = 1,
    val planned: Int = 0,
    val loaded: Int = 0,
    val sold: Int = 0,
    val returned: Int? = null,
    val missing: Int? = null,
    val plannedQty: PieceQty = PieceQty(),
    val loadedQty: PieceQty = PieceQty(),
    val soldQty: PieceQty = PieceQty(),
    // What is still on the vehicle.
    val onVehicle: Int = 0,
    val onVehicleQty: PieceQty = PieceQty(),
    // Phase 9 part 2: of what is on the vehicle, how much is held for the trip's own orders and
    // how much may be sold to anybody at a stop.
    val forOrders: Int = 0,
    val forOrdersQty: PieceQty = PieceQty(),
    val forSale: Int = 0,
    val forSaleQty: PieceQty = PieceQty(),
    val quantity: String = "",
    val batches: List<TripBatch> = emptyList(),
) {
    val label: String get() = listOf(brand, name, packSize).filter { it.isNotBlank() }.joinToString(" ")

    // The most a sale to somebody else may take. Worked out the same way as on the server, so a
    // pack saved by an older app, which knows nothing of orders, still sells the whole van.
    val sellable: Int get() = (onVehicle - forOrders).coerceAtLeast(0)
}

// --- Orders the trip delivers (Phase 9 part 2) ---

// One product on one of the trip's orders. toDeliver is all that is left while the trip is being
// planned, and what is on the vehicle for it once loaded. The price is missing for the warehouse.
@Serializable
data class TripOrderLine(
    val orderLineId: Int = 0,
    val productId: Int,
    val code: String = "",
    val brand: String = "",
    val name: String = "",
    val packSize: String = "",
    val companyCode: String = "",
    val piecesPerCarton: Int = 1,
    val vatExempt: Boolean = false,
    val pricePerCarton: Double? = null,
    val pieces: Int = 0,
    val delivered: Int = 0,
    val onVehicle: Int = 0,
    val toDeliver: Int = 0,
    val toDeliverQty: PieceQty = PieceQty(),
    val amount: Double? = null,
) {
    val label: String get() = listOf(brand, name, packSize).filter { it.isNotBlank() }.joinToString(" ")

    val perCarton: Int get() = piecesPerCarton.coerceAtLeast(1)
}

@Serializable
data class TripOrder(
    val id: Int,
    val number: String = "",
    val status: String = "open",
    val customerId: Int = 0,
    val customerName: String = "",
    val area: String = "",
    val cashOnly: Boolean = false,
    val invoiceType: String = "vat",
    val salesmanName: String? = null,
    val wantedOn: String = "",
    val lines: List<TripOrderLine> = emptyList(),
) {
    val toDeliver: Int get() = lines.sumOf { it.toDeliver }

    // Missing for the warehouse, which never sees what anything is worth.
    val amount: Double? get() = if (lines.any { it.amount == null }) null else lines.sumOf { it.amount ?: 0.0 }
}

// An order this trip could carry, as GET /trips/open-orders lists it.
@Serializable
data class OpenOrder(
    val id: Int,
    val number: String = "",
    val customerId: Int = 0,
    val customerName: String = "",
    val area: String = "",
    val channel: String = "",
    val wantedOn: String = "",
    val salesmanName: String? = null,
    val amountLeft: Double = 0.0,
    val products: Int = 0,
)

@Serializable
data class TripSaleInvoice(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val invoiceType: String = "vat",
    val total: Double = 0.0,
)

@Serializable
data class TripSalePayment(val id: Int, val number: String = "", val mode: String = "cash", val amount: Double = 0.0)

// payment: cash, airtel or credit.
@Serializable
data class TripSale(
    val id: Int,
    val customerId: Int = 0,
    val customerName: String = "",
    val stop: String = "",
    val payment: String = "cash",
    val total: Double = 0.0,
    val soldAt: String = "",
    val receivedBy: String = "",
    val soldBy: String = "",
    val invoices: List<TripSaleInvoice> = emptyList(),
)

// What one sale looks like when it comes back from the server, straight or through a sync.
@Serializable
data class TripSaleDetail(
    val id: Int,
    val requestId: String = "",
    val tripId: Int = 0,
    val tripNumber: String = "",
    val customerId: Int = 0,
    val customerName: String = "",
    val stop: String = "",
    val payment: String = "cash",
    val reference: String = "",
    val total: Double = 0.0,
    val receivedBy: String = "",
    val soldAt: String = "",
    val savedAt: String = "",
    val invoices: List<TripSaleInvoice> = emptyList(),
    val payments: List<TripSalePayment> = emptyList(),
)

@Serializable
data class TripExpense(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val amount: Double = 0.0,
    val description: String = "",
    val mode: String = "cash",
    val hasPhoto: Boolean = false,
    val createdAt: String = "",
)

@Serializable
data class TripDetail(
    val id: Int,
    val number: String = "",
    val kind: String = "upcountry",
    val facilityCode: String = "",
    val facilityName: String = "",
    val companyCode: String = "",
    val tripDate: String = "",
    val route: String = "",
    val stops: List<String> = emptyList(),
    val vehicleId: Int? = null,
    val vehiclePlate: String? = null,
    val driverId: Int? = null,
    val driverName: String? = null,
    val leaderId: Int = 0,
    val leaderName: String = "",
    val staff: String = "",
    val note: String = "",
    // Missing for the warehouse, along with every other money field.
    val float: Double? = null,
    val floatCustody: String = "na",
    val status: String = "planned",
    val gatePassNo: String? = null,
    val loadedAt: String? = null,
    val loadedBy: String? = null,
    val closedAt: String? = null,
    val closedBy: String? = null,
    val closeNote: String = "",
    val cashSales: Double? = null,
    val cashExpenses: Double? = null,
    val cashExpected: Double? = null,
    val cashCounted: Double? = null,
    val cashDifference: Double? = null,
    val cashNow: Double? = null,
    val createdBy: String = "",
    val createdAt: String = "",
    val lines: List<TripLine> = emptyList(),
    // The orders booked ahead that this trip delivers.
    val orders: List<TripOrder> = emptyList(),
    val sales: List<TripSale> = emptyList(),
    val expenses: List<TripExpense> = emptyList(),
    val salesTotal: Double? = null,
    val expensesTotal: Double? = null,
    val canEdit: Boolean = false,
    val canLoad: Boolean = false,
    val canSell: Boolean = false,
    val canClose: Boolean = false,
    // Only in the reply to loading: what was not on the shelves.
    val notLoaded: List<NotLoaded> = emptyList(),
) {
    val isMinibus: Boolean get() = kind == "minibus"

    val waitingToLoad: Boolean get() = status == "planned"

    val onTheRoad: Boolean get() = status == "out"

    val closed: Boolean get() = status == "closed"

    // The warehouse never sees what anything is worth.
    val showsMoney: Boolean get() = float != null

    // What the leader should have in hand: cash sales plus the float, less cash expenses.
    val cashInHand: Double get() = cashNow ?: 0.0

    // A warning when the trip has spent more than it was given.
    val overspent: Boolean get() = (expensesTotal ?: 0.0) > (float ?: 0.0)

    val floatLeft: Double get() = (float ?: 0.0) - (expensesTotal ?: 0.0)
}

// --- What the phone keeps for selling offline ---

@Serializable
data class PackCompany(val code: String = "", val owes: Double = 0.0)

// The last price this customer paid for a product, as the pack carries it.
@Serializable
data class PackPrice(val productId: Int, val pricePerCarton: Double = 0.0)

@Serializable
data class PackCustomer(
    val id: Int,
    val name: String = "",
    val area: String = "",
    val channel: String = "",
    val cashOnly: Boolean = false,
    val lastInvoiceType: String? = null,
    val companies: List<PackCompany>? = null,
    val lastPrices: List<PackPrice> = emptyList(),
) {
    fun lastPriceOf(productId: Int): Long? = lastPrices.firstOrNull { it.productId == productId }?.pricePerCarton?.toLong()

    val owes: Double get() = companies.orEmpty().sumOf { it.owes }
}

@Serializable
data class TripPack(
    val trip: TripDetail,
    val customers: List<PackCustomer> = emptyList(),
    val expenseKinds: List<String> = emptyList(),
)

// --- What the phone sends ---

@Serializable
data class TripLineQty(val productId: Int, val cartons: Int = 0, val pieces: Int = 0)

@Serializable
data class PlanTripRequest(
    val kind: String,
    val facilityCode: String,
    val companyCode: String? = null,
    val tripDate: String,
    val route: String,
    val stops: List<String> = emptyList(),
    val vehicleId: Int? = null,
    val driverId: Int? = null,
    val leaderId: Int,
    val staff: String = "",
    val note: String = "",
    val float: Int = 0,
    val lines: List<TripLineQty> = emptyList(),
    // Orders booked ahead that this trip delivers.
    val orderIds: List<Int> = emptyList(),
)

@Serializable
data class LoadTripRequest(val vehicleId: Int? = null, val lines: List<TripLineQty> = emptyList())

@Serializable
data class TripSaleLineQty(
    val productId: Int,
    val cartons: Int = 0,
    val pieces: Int = 0,
    // Left out when the sale delivers an order: the order's own price is used.
    val pricePerCarton: Int? = null,
)

// soldAt is the phone's own time, so a sale made offline keeps the moment it happened.
@Serializable
data class TripSaleRequest(
    val requestId: String,
    val customerId: Int,
    // Set when the sale delivers one of the trip's orders.
    val orderId: Int? = null,
    val stop: String = "",
    val invoiceType: String? = null,
    val payment: String = "cash",
    val reference: String = "",
    val receivedBy: String = "",
    val signature: String? = null,
    val proof: String? = null,
    val soldAt: String? = null,
    val lines: List<TripSaleLineQty> = emptyList(),
)

@Serializable
data class TripExpenseRequest(
    val requestId: String,
    val companyCode: String? = null,
    val amount: Int,
    val description: String,
    val photo: String? = null,
)

@Serializable
data class CloseTripRequest(val lines: List<TripLineQty> = emptyList(), val counted: Int, val note: String = "")

@Serializable
data class SyncRequest(val sales: List<TripSaleRequest> = emptyList(), val expenses: List<TripExpenseRequest> = emptyList())

// One item's answer from a sync. A repeat counts as ok: the server already had it.
@Serializable
data class SyncItemResult(
    val requestId: String? = null,
    val ok: Boolean = false,
    val repeat: Boolean = false,
    val error: String? = null,
    val message: String? = null,
)

@Serializable
data class SyncResult(
    val sales: List<SyncItemResult> = emptyList(),
    val expenses: List<SyncItemResult> = emptyList(),
    val trip: TripDetail? = null,
)
