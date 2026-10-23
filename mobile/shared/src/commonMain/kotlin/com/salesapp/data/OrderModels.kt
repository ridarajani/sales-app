package com.salesapp.data

import kotlinx.serialization.Serializable

// Sales orders (Phase 4). Money is whole kwacha; quantities are pieces unless the name says cartons.

// One row of the orders list. total includes VAT.
@Serializable
data class OrderListItem(
    val id: Int,
    val number: String = "",
    val status: String = "open",
    val invoiceType: String = "vat",
    val wantedOn: String? = null,
    val createdAt: String = "",
    val customerId: Int = 0,
    val customerName: String = "",
    val area: String = "",
    val salesmanName: String? = null,
    val companies: List<OrderCompanyRef> = emptyList(),
    val shortPieces: Int = 0,
    val total: Long = 0,
)

@Serializable
data class OrderCompanyRef(val code: String, val lpoNo: String? = null)

// What the phone needs to book an order for one customer.
@Serializable
data class BookingInfo(
    val customer: BookingCustomer,
    val companies: List<BookingCompany> = emptyList(),
    val lastPrices: List<LastPrice> = emptyList(),
    val minShelfDays: Int = 0,
)

@Serializable
data class BookingCustomer(
    val id: Int,
    val name: String,
    val channel: String = "",
    val area: String = "",
    val needsLpo: Boolean = false,
    val cashOnly: Boolean = false,
    // "vat", "plain" or null when the customer has never ordered.
    val lastInvoiceType: String? = null,
)

// A company of the user's that the customer buys from. balance is what the shop owes that company.
@Serializable
data class BookingCompany(
    val code: String,
    val name: String = "",
    val facilityCode: String = "",
    val paymentTermsDays: Int = 0,
    val balance: Long = 0,
)

@Serializable
data class LastPrice(val productId: Int, val pricePerCarton: Long)

@Serializable
data class NewOrderRequest(
    val customerId: Int,
    val invoiceType: String,
    // Ignored by the server when a salesman books.
    val salesmanId: Int? = null,
    val wantedOn: String? = null,
    val note: String = "",
    // Company code -> LPO number.
    val lpos: Map<String, String> = emptyMap(),
    val lines: List<NewOrderLine>,
)

// pieces are loose pieces on top of the cartons.
@Serializable
data class NewOrderLine(
    val productId: Int,
    val cartons: Int = 0,
    val pieces: Int = 0,
    val pricePerCarton: Long,
)

// One order with its lines, totals per company and history.
@Serializable
data class OrderDetail(
    val id: Int,
    val number: String = "",
    val status: String = "open",
    val facilityCode: String = "",
    val invoiceType: String = "vat",
    val wantedOn: String? = null,
    val note: String = "",
    val createdAt: String = "",
    val updatedAt: String = "",
    val customerId: Int = 0,
    val customerName: String = "",
    val channel: String = "",
    val area: String = "",
    val needsLpo: Boolean = false,
    val salesmanId: Int? = null,
    val salesmanName: String? = null,
    val bookedById: Int = 0,
    val bookedBy: String = "",
    val companies: List<OrderCompanyTotals> = emptyList(),
    val lines: List<OrderLine> = emptyList(),
    // Oldest first.
    val history: List<OrderHistoryEntry> = emptyList(),
    val subtotal: Long = 0,
    val vat: Long = 0,
    val total: Long = 0,
    val shortPieces: Int = 0,
    val canChange: Boolean = false,
    val canManage: Boolean = false,
)

// vatRate is 0 on a plain invoice.
@Serializable
data class OrderCompanyTotals(
    val code: String,
    val name: String = "",
    val lpoNo: String? = null,
    val subtotal: Long = 0,
    val vatRate: Double = 0.0,
    val vat: Long = 0,
    val total: Long = 0,
)

// pieces is the total ordered; cartons and loosePieces are the same amount split up. amount excludes VAT.
@Serializable
data class OrderLine(
    val id: Int,
    val productId: Int = 0,
    val code: String = "",
    val brand: String = "",
    val name: String = "",
    val packSize: String = "",
    val companyCode: String = "",
    val piecesPerCarton: Int = 1,
    val pieces: Int = 0,
    val pricePerCarton: Long = 0,
    val delivered: Int = 0,
    val reserved: Int = 0,
    val cartons: Int = 0,
    val loosePieces: Int = 0,
    val amount: Long = 0,
    val shortPieces: Int = 0,
)

@Serializable
data class OrderHistoryEntry(
    val title: String = "",
    val detail: String = "",
    val by: String = "",
    val at: String = "",
)

// Only what is set is sent.
@Serializable
data class OrderChange(
    val invoiceType: String? = null,
    val wantedOn: String? = null,
    val note: String? = null,
    val lpos: Map<String, String>? = null,
    val lines: List<OrderLineChange>? = null,
)

@Serializable
data class OrderLineChange(
    val lineId: Int,
    val cartons: Int = 0,
    val pieces: Int = 0,
    val pricePerCarton: Long,
)

@Serializable
data class OrderNote(val note: String = "")
