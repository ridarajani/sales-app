package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// Screens for money (Phase 7): payments, expenses, cash and Airtel Money wallets.

// Recording a payment. Opened empty from Home, or with everything picked from an invoice.
@Serializable
data class CollectMoneyRoute(
    val customerId: Int? = null,
    val companyCode: String? = null,
    val invoiceId: Int? = null,
)

@Serializable
data object PaymentsRoute

@Serializable
data class PaymentDetailRoute(val id: Int)

@Serializable
data object ExpensesRoute

@Serializable
data object NewExpenseRoute

// What this person holds and has not handed over yet.
@Serializable
data object MyCashRoute

// The cashier's list of cash waiting to be counted.
@Serializable
data object CountCashRoute

@Serializable
data class HandoverDetailRoute(val id: Int)

@Serializable
data object WalletsRoute

@Serializable
data class WalletDetailRoute(val id: Int)

// Admin sets up a wallet, or changes the one with this id.
@Serializable
data class WalletFormRoute(val id: Int? = null)
