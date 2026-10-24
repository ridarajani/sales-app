package com.salesapp.data

import kotlinx.serialization.Serializable

// Invoices: one per customer, company, LPO and invoice type, made from a loading plan for what
// actually went on the vehicle. Money is whole kwacha; the server sends it as a number.

// A row in the invoice register.
@Serializable
data class InvoiceListItem(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val invoiceType: String = "vat",
    val customerId: Int = 0,
    val customerName: String = "",
    val area: String = "",
    val lpoNo: String = "",
    val invoiceDate: String = "",
    val dueDate: String = "",
    val subtotal: Double = 0.0,
    val vat: Double = 0.0,
    val total: Double = 0.0,
    // Phase 7: what is still owed on it.
    val balance: Double = 0.0,
    val salesmanName: String? = null,
    val vehiclePlate: String? = null,
    val tripNumber: String? = null,
    val delivered: Boolean = false,
) {
    val isVat: Boolean get() = invoiceType == "vat"
}

@Serializable
data class InvoiceCustomer(
    val id: Int = 0,
    val name: String = "",
    val address: String? = null,
    val city: String? = null,
    val area: String? = null,
    val regNo: String? = null,
    val tpin: String? = null,
)

@Serializable
data class InvoiceCompany(
    val code: String = "",
    val name: String = "",
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val tpin: String? = null,
)

// quantity is the server's own wording for the amount, for example "4 ctn" or "4 ctn 3 pcs".
@Serializable
data class InvoiceLine(
    // The invoice line's own id, which a sales return points at.
    val lineId: Int = 0,
    val productId: Int = 0,
    val code: String = "",
    val brand: String = "",
    val name: String = "",
    val packSize: String = "",
    val piecesPerCarton: Int = 1,
    val pieces: Int = 0,
    val quantity: String = "",
    val pricePerCarton: Double = 0.0,
    val amount: Double = 0.0,
    // Empty on a trip invoice: there is no order behind it.
    val orderNumber: String? = null,
    // Phase 8. delivered is null until the drop is confirmed; canReturn is the most that can still
    // come back after delivery (delivered less what already came back).
    val delivered: Int? = null,
    val returned: Int = 0,
    val canReturn: Int? = null,
)

@Serializable
data class InvoiceDetail(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val invoiceType: String = "vat",
    val planId: Int? = null,
    val lpoNo: String = "",
    val invoiceDate: String = "",
    val dueDate: String = "",
    val subtotal: Double = 0.0,
    val vatRate: Double = 0.0,
    val vat: Double = 0.0,
    val total: Double = 0.0,
    // Phase 7: what is still owed, what came off it, and the payments against it.
    val balance: Double = 0.0,
    val discount: Double = 0.0,
    val wht: Double = 0.0,
    val payments: List<InvoicePayment> = emptyList(),
    // Phase 8: what came back and the notes made on it.
    val returns: List<InvoiceReturn> = emptyList(),
    val notes: List<InvoiceNoteRow> = emptyList(),
    // Whoever may book a return or make a note: admin, management, sales managers and accounts.
    val canAdjust: Boolean = false,
    val createdAt: String = "",
    val createdBy: String = "",
    val salesmanName: String? = null,
    val planNumber: String? = null,
    val deliveryNoteNo: String? = null,
    val vehiclePlate: String? = null,
    val deliveredAt: String? = null,
    // Phase 9: an invoice made on a trip has no order behind it; it names the trip and the stop.
    val tripId: Int? = null,
    val tripNumber: String? = null,
    val tripStop: String? = null,
    val customer: InvoiceCustomer = InvoiceCustomer(),
    val company: InvoiceCompany = InvoiceCompany(),
    val lines: List<InvoiceLine> = emptyList(),
) {
    val isVat: Boolean get() = invoiceType == "vat"
}

// One payment that went against an invoice. amount is what went on this invoice, not the whole payment.
@Serializable
data class InvoicePayment(
    val id: Int,
    val number: String = "",
    val mode: String = "cash",
    val amount: Double = 0.0,
    val at: String = "",
)

// A return on this invoice. It only comes off the invoice once it is booked.
@Serializable
data class InvoiceReturn(
    val id: Int,
    val number: String = "",
    val kind: String = "after",
    val reason: String = "",
    val total: Double = 0.0,
    val booked: Boolean = false,
    val goods: String = "waiting",
    val at: String = "",
) {
    val notTakenAtDelivery: Boolean get() = kind == "delivery"
}

// A credit or debit note on this invoice; owesChange is + when the customer owes more.
@Serializable
data class InvoiceNoteRow(
    val id: Int,
    val number: String = "",
    val kind: String = "credit_issued",
    val reason: String = "",
    val amount: Double = 0.0,
    val owesChange: Double = 0.0,
    val theirNumber: String = "",
    val at: String = "",
)
