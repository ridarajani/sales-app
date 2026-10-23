package com.salesapp.data

import kotlinx.serialization.Serializable

// Loading plans: which open orders go on which vehicle. Quantities are pieces unless the name says cartons.
// In replies, pieces is the total and cartons + loosePieces is the same amount split up.

@Serializable
data class PieceSplit(val pieces: Int = 0, val cartons: Int = 0, val loosePieces: Int = 0)

// status: planned, loaded, out or done. sent only means something while planned.
@Serializable
data class LoadingPlanListItem(
    val id: Int,
    val number: String = "",
    val facilityCode: String = "",
    val status: String = "planned",
    val sent: Boolean = false,
    val vehiclePlate: String? = null,
    val driverName: String? = null,
    val deliveryNoteNo: String? = null,
    val gatePassNo: String? = null,
    val createdAt: String = "",
    val customers: Int = 0,
    val pieces: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
    val newChanges: Int = 0,
)

@Serializable
data class PlanDetail(
    val id: Int,
    val number: String = "",
    val facilityCode: String = "",
    val facilityName: String = "",
    val status: String = "planned",
    val sent: Boolean = false,
    val sentAt: String? = null,
    val vehicleId: Int? = null,
    val vehiclePlate: String? = null,
    val vehicleDescription: String? = null,
    val driverId: Int? = null,
    val driverName: String? = null,
    val note: String = "",
    val deliveryNoteNo: String? = null,
    val gatePassNo: String? = null,
    val loadedAt: String? = null,
    val loadedBy: String? = null,
    val outAt: String? = null,
    val outBy: String? = null,
    val createdBy: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    // The warehouse view: product totals with the batches to pick.
    val products: List<PlanProduct> = emptyList(),
    // The driver view: one per shop.
    val drops: List<PlanDrop> = emptyList(),
    // Full history, oldest first.
    val changes: List<PlanChange> = emptyList(),
    val newChanges: Int = 0,
    val canPlan: Boolean = false,
    val canSend: Boolean = false,
    val canWarehouseChange: Boolean = false,
    val canLoad: Boolean = false,
    val canGate: Boolean = false,
    val canDelete: Boolean = false,
    // Only in the reply to a warehouse change: what did not fit, in pieces.
    val notAdded: List<NotAdded> = emptyList(),
    // Only in the reply to Loaded: what was not on the shelves.
    val notLoaded: List<NotLoaded> = emptyList(),
)

// pieces, cartons and loosePieces are the planned amount; loaded is pieces loaded (0 until loaded).
@Serializable
data class PlanProduct(
    val productId: Int,
    val code: String = "",
    val brand: String = "",
    val name: String = "",
    val packSize: String = "",
    val companyCode: String = "",
    val piecesPerCarton: Int = 1,
    val pieces: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
    val loaded: Int = 0,
    val loadedCartons: PieceSplit = PieceSplit(),
    val items: List<PlanItem> = emptyList(),
    // While planned: the batches to pick, oldest expiry first. After loading: the batches loaded.
    val batches: List<PlanBatch> = emptyList(),
)

@Serializable
data class PlanItem(
    val itemId: Int,
    val lineId: Int = 0,
    val orderId: Int = 0,
    val orderNumber: String = "",
    val customerId: Int = 0,
    val customerName: String = "",
    val area: String = "",
    val lpoNo: String? = null,
    val loaded: Int = 0,
    val pieces: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
)

@Serializable
data class PlanBatch(
    val batchId: Int,
    val batchNo: String = "",
    val expiryDate: String = "",
    val pieces: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
)

@Serializable
data class PlanDrop(
    val customerId: Int,
    val customerName: String = "",
    val area: String = "",
    // Order numbers.
    val orders: List<String> = emptyList(),
    val lines: List<PlanDropLine> = emptyList(),
)

// The amount is planned while the plan is planned, loaded after that.
@Serializable
data class PlanDropLine(
    val orderNumber: String = "",
    val companyCode: String = "",
    val lpoNo: String? = null,
    val productId: Int = 0,
    val code: String = "",
    val brand: String = "",
    val name: String = "",
    val packSize: String = "",
    val pieces: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
)

// before and after are pieces. Planner changes have an order and shop; warehouse changes have a reason.
@Serializable
data class PlanChange(
    val id: Long,
    val byWarehouse: Boolean = false,
    val by: String = "",
    val at: String = "",
    val productId: Int = 0,
    // "brand name pack"
    val product: String = "",
    val piecesPerCarton: Int = 1,
    val orderNumber: String? = null,
    val customerName: String? = null,
    val before: Int = 0,
    val after: Int = 0,
    val reason: String = "",
    val seen: Boolean = false,
)

@Serializable
data class NotAdded(val productId: Int, val pieces: Int = 0)

@Serializable
data class NotLoaded(val product: String = "", val pieces: Int = 0, val cartons: Int = 0, val loosePieces: Int = 0)

// An open order product for the planner. open is the most this plan may carry; current is what it carries now.
@Serializable
data class OpenProduct(
    val productId: Int,
    val code: String = "",
    val brand: String = "",
    val name: String = "",
    val packSize: String = "",
    val companyCode: String = "",
    val piecesPerCarton: Int = 1,
    val open: Int = 0,
    val current: Int = 0,
    val lines: List<OpenLine> = emptyList(),
)

// A line is ticked when current > 0.
@Serializable
data class OpenLine(
    val lineId: Int,
    val orderId: Int = 0,
    val orderNumber: String = "",
    val wantedOn: String? = null,
    val customerId: Int = 0,
    val customerName: String = "",
    val area: String = "",
    val channel: String = "",
    val lpoNo: String? = null,
    val open: Int = 0,
    val current: Int = 0,
    val openCartons: PieceSplit = PieceSplit(),
    val currentCartons: PieceSplit = PieceSplit(),
)

@Serializable
data class NewPlanRequest(
    val facilityCode: String,
    val vehicleId: Int? = null,
    val driverId: Int? = null,
    val note: String = "",
)

// Only what is sent changes. A line sent with 0 comes off the plan; lines left out stay as they are.
@Serializable
data class PlanItemsRequest(
    val vehicleId: Int? = null,
    val driverId: Int? = null,
    val note: String? = null,
    val items: List<PlanItemQty>? = null,
)

// pieces are loose pieces on top of the cartons.
@Serializable
data class PlanItemQty(val lineId: Int, val cartons: Int = 0, val pieces: Int = 0)

@Serializable
data class WarehouseChangeRequest(val products: List<ProductQty>, val reason: String)

// The new total for the product across the whole plan; pieces are loose pieces on top of the cartons.
@Serializable
data class ProductQty(val productId: Int, val cartons: Int = 0, val pieces: Int = 0)
