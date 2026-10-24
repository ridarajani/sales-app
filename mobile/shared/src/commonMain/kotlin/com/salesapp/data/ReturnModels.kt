package com.salesapp.data

import kotlinx.serialization.Serializable

// Phase 8: goods that came back, and credit or debit notes on an invoice.
// The warehouse sees returns without their money, so every money field here can be missing.

// kind: "delivery" (not taken at delivery) or "after" (brought back later).
// goods: "waiting" for the warehouse, or "received".
@Serializable
data class ReturnListItem(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val customerName: String = "",
    val invoiceId: Int = 0,
    val invoiceNumber: String = "",
    val kind: String = "after",
    val reason: String = "",
    // Missing for the warehouse.
    val total: Double? = null,
    val booked: Boolean = false,
    val goods: String = "waiting",
    val products: String? = null,
    val createdAt: String = "",
) {
    val notTakenAtDelivery: Boolean get() = kind == "delivery"

    val waitingForWarehouse: Boolean get() = goods == "waiting"
}

// condition: "good" or "damaged", set by the warehouse when the goods come back.
@Serializable
data class ReturnLine(
    val id: Int,
    val invoiceLineId: Int = 0,
    val productId: Int = 0,
    val brand: String = "",
    val name: String = "",
    val packSize: String = "",
    val piecesPerCarton: Int = 1,
    val pieces: Int = 0,
    val quantity: String = "",
    val amount: Double? = null,
    val condition: String = "good",
)

@Serializable
data class ReturnDetail(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val customerId: Int = 0,
    val customerName: String = "",
    val invoiceId: Int = 0,
    val invoiceNumber: String = "",
    val kind: String = "after",
    val reason: String = "",
    val note: String = "",
    val subtotal: Double? = null,
    val vat: Double? = null,
    val total: Double? = null,
    val booked: Boolean = false,
    val bookedBy: String? = null,
    val bookedAt: String? = null,
    val goods: String = "waiting",
    val receivedBy: String? = null,
    val receivedAt: String? = null,
    val createdBy: String = "",
    val createdAt: String = "",
    val deliveryNoteNo: String? = null,
    val lines: List<ReturnLine> = emptyList(),
    val canBook: Boolean = false,
    val canReceive: Boolean = false,
) {
    val notTakenAtDelivery: Boolean get() = kind == "delivery"

    val waitingForWarehouse: Boolean get() = goods == "waiting"

    // The warehouse never sees what the goods cost.
    val showsMoney: Boolean get() = total != null
}

// Goods given back after delivery. pieces are loose pieces on top of the cartons.
@Serializable
data class ReturnRequest(
    val lines: List<ReturnLineQty> = emptyList(),
    val reason: String,
    val note: String = "",
    val requestId: String? = null,
)

@Serializable
data class ReturnLineQty(val invoiceLineId: Int, val cartons: Int = 0, val pieces: Int = 0)

// A line left out of `damaged` came back in good order.
@Serializable
data class ReceiveRequest(val damaged: List<Int> = emptyList())

// kind: credit_issued, debit_issued, debit_received or credit_received.
// owesChange is what the customer owes more (+) or less (−) because of it.
@Serializable
data class InvoiceNoteDetail(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val customerId: Int = 0,
    val customerName: String = "",
    val invoiceId: Int = 0,
    val invoiceNumber: String = "",
    val kind: String = "credit_issued",
    val amount: Double = 0.0,
    val owesChange: Double = 0.0,
    val reason: String = "",
    val theirNumber: String = "",
    val note: String = "",
    val createdBy: String = "",
    val createdAt: String = "",
    val invoiceBalance: Double = 0.0,
    // The server's own words for the kind.
    val label: String = "",
)

@Serializable
data class NoteRequest(
    val kind: String,
    val amount: Int,
    val reason: String,
    val theirNumber: String = "",
    val note: String = "",
    val requestId: String? = null,
)

// The reasons the server will accept, in the prototype's order.
val RETURN_REASONS: List<String> = listOf(
    "Damaged goods",
    "Short-dated or expired",
    "Wrong product",
    "Customer refused delivery",
    "Quality complaint",
    "Commercial return",
    "Other",
)

val NOTE_REASONS: List<String> = listOf(
    "Price difference",
    "Wrong price on the invoice",
    "Promotion agreed later",
    "Other",
)

// The four kinds of note, and whether the customer ends up owing more.
val NOTE_KINDS: List<String> = listOf("credit_issued", "debit_issued", "debit_received", "credit_received")

// A received note is the customer's own, so it needs their number.
fun noteNeedsTheirNumber(kind: String): Boolean = kind == "debit_received" || kind == "credit_received"

fun noteMakesThemOweMore(kind: String): Boolean = kind == "debit_issued" || kind == "credit_received"
