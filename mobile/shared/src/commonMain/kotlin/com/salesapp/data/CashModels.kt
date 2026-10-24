package com.salesapp.data

import kotlinx.serialization.Serializable

// Phase 7: payments from customers, expenses, and cash handed to the cashier.
// Money is whole kwacha; the server sends it as a number.

// --- Collecting a payment ---

@Serializable
data class CollectCustomer(val id: Int, val name: String = "", val area: String = "", val cashOnly: Boolean = false)

// One invoice of this customer that still owes something.
@Serializable
data class OpenInvoice(
    val id: Int,
    val number: String = "",
    val invoiceType: String = "vat",
    val lpoNo: String = "",
    val invoiceDate: String = "",
    val dueDate: String = "",
    val total: Double = 0.0,
    val balance: Double = 0.0,
    val overdue: Boolean = false,
)

// What the customer owes one company, and the invoices still open there.
@Serializable
data class CollectCompany(
    val code: String,
    val name: String = "",
    val owes: Double = 0.0,
    val openingLeft: Double = 0.0,
    val invoices: List<OpenInvoice> = emptyList(),
)

// What the collect screen needs once a customer is picked. signatureNeeded is false only for the
// office roles, who record a transfer without the customer there.
@Serializable
data class CollectInfo(
    val customer: CollectCustomer,
    val companies: List<CollectCompany> = emptyList(),
    val signatureNeeded: Boolean = true,
)

// A payment being saved. The signature and proof photo are base64.
@Serializable
data class CollectRequest(
    val customerId: Int,
    val companyCode: String,
    val invoiceId: Int? = null,
    val mode: String = "cash",
    val amount: Int = 0,
    val discount: Int = 0,
    val wht: Int = 0,
    val whtCertificate: String = "",
    val reference: String = "",
    val note: String = "",
    val receivedBy: String = "",
    val signature: String? = null,
    val proof: String? = null,
)

// Where one payment went: an allocation with no invoice is the opening balance.
@Serializable
data class Allocation(val invoiceId: Int? = null, val invoiceNumber: String? = null, val amount: Double = 0.0)

// A row in the payments list.
@Serializable
data class PaymentListItem(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val customerName: String = "",
    val invoiceNumber: String? = null,
    val mode: String = "cash",
    val amount: Double = 0.0,
    val discount: Double = 0.0,
    val wht: Double = 0.0,
    val reference: String = "",
    val collectedBy: String = "",
    val custody: String = "na",
    val createdAt: String = "",
)

@Serializable
data class PaymentDetail(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val customerId: Int = 0,
    val customerName: String = "",
    val invoiceId: Int? = null,
    val invoiceNumber: String? = null,
    val mode: String = "cash",
    val amount: Double = 0.0,
    val discount: Double = 0.0,
    val wht: Double = 0.0,
    val whtCertificate: String = "",
    val reference: String = "",
    val note: String = "",
    val receivedBy: String = "",
    val hasSignature: Boolean = false,
    val hasProof: Boolean = false,
    val collectedBy: String = "",
    val custody: String = "na",
    val handoverNumber: String? = null,
    val createdAt: String = "",
    val allocations: List<Allocation> = emptyList(),
    // Anything over what the invoices owed stays on account.
    val onAccount: Double = 0.0,
    val owesNow: Double = 0.0,
)

// --- Expenses ---

@Serializable
data class Expense(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val amount: Double = 0.0,
    val description: String = "",
    val mode: String = "cash",
    val hasPhoto: Boolean = false,
    val spentBy: String = "",
    val custody: String = "na",
    val handoverNumber: String? = null,
    val createdAt: String = "",
)

@Serializable
data class ExpenseRequest(
    val companyCode: String,
    val amount: Int,
    val description: String,
    val mode: String = "cash",
    val photo: String? = null,
)

// --- Cash held, and handing it over ---

// One company's share of what a person holds: cash collected plus Airtel withdrawals, less cash expenses.
@Serializable
data class CashCompany(
    val code: String,
    val cash: Double = 0.0,
    val withdrawals: Double = 0.0,
    val expenses: Double = 0.0,
    val net: Double = 0.0,
)

@Serializable
data class HeldCollection(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val customerName: String = "",
    val amount: Double = 0.0,
    val createdAt: String = "",
)

@Serializable
data class HeldWithdrawal(val id: Int, val companyCode: String = "", val amount: Double = 0.0, val createdAt: String = "")

@Serializable
data class CashHeld(
    val collections: List<HeldCollection> = emptyList(),
    val withdrawals: List<HeldWithdrawal> = emptyList(),
    val expenses: List<Expense> = emptyList(),
    val companies: List<CashCompany> = emptyList(),
    val cash: Double = 0.0,
    val withdrawalsTotal: Double = 0.0,
    val expensesTotal: Double = 0.0,
    val net: Double = 0.0,
)

@Serializable
data class HandoverListItem(
    val id: Int,
    val number: String = "",
    val handedBy: String = "",
    val cash: Double = 0.0,
    val withdrawals: Double = 0.0,
    val expenses: Double = 0.0,
    val net: Double = 0.0,
    val status: String = "handed",
    val counted: Double? = null,
    val difference: Double? = null,
    val createdAt: String = "",
    val countedAt: String? = null,
) {
    val waiting: Boolean get() = status == "handed"
}

@Serializable
data class HandoverExpense(
    val id: Int,
    val number: String = "",
    val companyCode: String = "",
    val description: String = "",
    val amount: Double = 0.0,
    val hasPhoto: Boolean = false,
)

@Serializable
data class HandoverDetail(
    val id: Int,
    val number: String = "",
    val handedById: Int = 0,
    val handedBy: String = "",
    val cash: Double = 0.0,
    val withdrawals: Double = 0.0,
    val expenses: Double = 0.0,
    val net: Double = 0.0,
    val status: String = "handed",
    val counted: Double? = null,
    val difference: Double? = null,
    val note: String = "",
    val countedBy: String? = null,
    val countedAt: String? = null,
    val createdAt: String = "",
    val collections: List<HeldCollection> = emptyList(),
    val expenseList: List<HandoverExpense> = emptyList(),
    val withdrawalList: List<HeldWithdrawal> = emptyList(),
    val companies: List<CashCompany> = emptyList(),
    // False for the person who handed it over: someone else must count it.
    val canCount: Boolean = false,
) {
    val waiting: Boolean get() = status == "handed"
}

@Serializable
data class CashCountRequest(val counted: Int, val note: String = "")
