package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// Screens for Phase 8: returns, notes and the customer ledger.

@Serializable
data object ReturnsRoute

@Serializable
data class ReturnDetailRoute(val id: Int)

// Goods given back after delivery, made from the invoice.
@Serializable
data class NewReturnRoute(val invoiceId: Int)

// A credit or debit note, made from the invoice.
@Serializable
data class NewNoteRoute(val invoiceId: Int)

// The ledger card. Opened empty from Home, or with the shop and company already picked.
@Serializable
data class LedgerRoute(val customerId: Int? = null, val companyCode: String? = null)
