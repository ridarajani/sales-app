package com.salesapp.data

import kotlinx.serialization.Serializable

// Phase 8: the customer ledger card, every movement with one company, oldest first.

@Serializable
data class LedgerCustomer(
    val id: Int = 0,
    val name: String = "",
    val area: String? = null,
    val address: String? = null,
    val city: String? = null,
)

@Serializable
data class LedgerCompany(
    val code: String = "",
    val name: String = "",
    val address: String? = null,
    val phone: String? = null,
    val email: String? = null,
    val tpin: String? = null,
)

// One movement: charged puts the balance up, paid puts it down.
@Serializable
data class LedgerRow(
    val date: String = "",
    val document: String = "",
    val what: String = "",
    val charged: Double = 0.0,
    val paid: Double = 0.0,
    val balance: Double = 0.0,
    val invoiceId: Int? = null,
)

@Serializable
data class Ledger(
    val customer: LedgerCustomer = LedgerCustomer(),
    val company: LedgerCompany = LedgerCompany(),
    val from: String? = null,
    val to: String? = null,
    // What was owed before the period started.
    val opening: Double = 0.0,
    val rows: List<LedgerRow> = emptyList(),
    val charged: Double = 0.0,
    val paid: Double = 0.0,
    val closing: Double = 0.0,
    val owes: Double = 0.0,
    val overdue: Double = 0.0,
)
