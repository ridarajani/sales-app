package com.salesapp.data

// Who may do what. These mirror the rules on the server.

// Admin and accounts change any customer; a sales manager only customers whose companies are all theirs.
// When the server says (canEdit), that answer counts too, because the phone only sees some companies.
fun User.canEditCustomers(customer: Customer? = null): Boolean {
    val byRole = when (role) {
        "admin", "accounts" -> true
        "sales_manager" -> customer == null || customer.companies.all { it.code in companies }
        else -> false
    }
    return byRole && customer?.canEdit != false
}

fun User.canAddCustomers(): Boolean = role == "admin" || role == "accounts" || role == "sales_manager"

fun User.canSeeOpeningBalances(): Boolean = role == "admin" || role == "accounts" || role == "management"

fun User.canEnterOpeningBalance(): Boolean = role == "admin" || role == "accounts"

fun User.canCorrectOpeningBalance(): Boolean = role == "admin"

fun User.canEditProducts(): Boolean = role == "admin" || role == "accounts"

fun User.canEditVehicles(): Boolean = role == "admin"

fun User.canEditCompanies(): Boolean = role == "admin"

fun User.canSeeStock(): Boolean = role == "admin" || role == "management" || role == "warehouse"

fun User.canApproveStock(): Boolean = role == "admin" || role == "management"

fun User.canKeepStock(): Boolean = role == "admin" || role == "warehouse"
