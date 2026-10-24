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
    val orderNumber: String = "",
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
    val createdAt: String = "",
    val createdBy: String = "",
    val salesmanName: String? = null,
    val planNumber: String? = null,
    val deliveryNoteNo: String? = null,
    val vehiclePlate: String? = null,
    val deliveredAt: String? = null,
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
