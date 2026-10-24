package com.salesapp.data

import kotlinx.serialization.Serializable

// Phase 7: one Airtel Money wallet per sales manager, for their companies. Payments in are credits
// and wait until the holder ticks them; expenses, withdrawals and charges are debits.

@Serializable
data class WalletListItem(
    val id: Int,
    val holderName: String = "",
    val holderId: Int = 0,
    val active: Boolean = true,
    val balance: Double = 0.0,
    val companies: List<String> = emptyList(),
    // Payments that have not been ticked yet.
    val waiting: Int = 0,
)

// kind: payment, expense, withdrawal or charge. One of credit or debit is 0.
@Serializable
data class WalletEntry(
    val id: Int,
    val companyCode: String = "",
    val kind: String = "payment",
    val credit: Double = 0.0,
    val debit: Double = 0.0,
    val description: String = "",
    val reference: String = "",
    val status: String = "ok",
    val tickedAt: String? = null,
    val custody: String = "na",
    val collectionNumber: String? = null,
    val expenseNumber: String? = null,
    val by: String = "",
    val createdAt: String = "",
    // The wallet's balance after this entry.
    val balanceAfter: Double = 0.0,
) {
    val waiting: Boolean get() = status == "waiting"

    val isCredit: Boolean get() = credit > 0

    val amount: Double get() = if (isCredit) credit else debit
}

// One time the holder compared the app with the real wallet on their phone.
@Serializable
data class WalletCheck(
    val id: Int,
    val actual: Double = 0.0,
    val ledger: Double = 0.0,
    val difference: Double = 0.0,
    val note: String = "",
    val by: String = "",
    val at: String = "",
)

@Serializable
data class WalletDetail(
    val id: Int,
    val holderId: Int = 0,
    val holderName: String = "",
    val openingBalance: Double = 0.0,
    val active: Boolean = true,
    val balance: Double = 0.0,
    val companies: List<String> = emptyList(),
    val waiting: Int = 0,
    val entries: List<WalletEntry> = emptyList(),
    val checks: List<WalletCheck> = emptyList(),
    // True for the holder and for admin: they may tick a payment and check the balance.
    val canAct: Boolean = false,
    // Only the holder takes money out, because it becomes cash in their own hand.
    val canTakeOut: Boolean = false,
)

// Admin sets up or changes a wallet.
@Serializable
data class WalletRequest(
    val holderId: Int,
    val openingBalance: Int = 0,
    val companies: List<String> = emptyList(),
    val active: Boolean = true,
)

// The money taken out becomes cash in the holder's hand; the Airtel charge is recorded too.
@Serializable
data class WithdrawRequest(val companyCode: String, val amount: Int, val charge: Int = 0)

@Serializable
data class WalletCheckRequest(val actual: Int, val note: String = "")
