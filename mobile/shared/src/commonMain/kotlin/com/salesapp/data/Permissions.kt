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

// Orders: everyone but drivers can see them (a salesman only his own).
fun User.canSeeOrders(): Boolean = role in setOf("admin", "management", "sales_manager", "salesman", "warehouse", "cashier", "accounts")

fun User.canBookOrders(): Boolean = role == "admin" || role == "sales_manager" || role == "salesman"

// Who picks the salesman when booking; a salesman always books for himself.
fun User.picksSalesman(): Boolean = role == "admin" || role == "sales_manager"

// Salesmen never see stock: no Short pill, no "not in stock yet", no Reserve again.
fun User.seesOrderStock(): Boolean = role != "salesman"

// Loading plans. These mirror PLAN_VIEWERS, PLANNERS and LOADERS on the server. Salesmen never see plans.
fun User.canSeePlans(): Boolean = role in setOf("admin", "management", "sales_manager", "warehouse", "driver", "accounts", "cashier")

fun User.canPlanLoading(): Boolean = role == "admin" || role == "management" || role == "sales_manager"

fun User.canLoadVehicles(): Boolean = role == "admin" || role == "warehouse"

// Who is told about changes from the other side and may say "OK, seen".
fun User.seesPlanChanges(): Boolean = role == "admin" || role == "management" || role == "sales_manager" || role == "warehouse"

// Whether a plan change counts as new for this user: the warehouse hears about planner changes,
// admin about every change, everyone else about warehouse changes. Same rule as the server.
fun User.isNewFor(byWarehouse: Boolean): Boolean = when (role) {
    "warehouse" -> !byWarehouse
    "admin" -> true
    else -> byWarehouse
}

fun User.isDriver(): Boolean = role == "driver"

// Invoices (Phase 6). Warehouse staff never see them; everyone else sees their own companies'
// (a salesman only his own, a driver only his vehicles'). Same as INVOICE_VIEWERS on the server.
fun User.canSeeInvoices(): Boolean = role in setOf("admin", "management", "sales_manager", "salesman", "driver", "cashier", "accounts")

// Phase 7: money. These mirror COLLECTORS, COLLECTION_VIEWERS, EXPENSE_MAKERS, CASH_HOLDERS,
// CASH_COUNTERS and the wallet viewers on the server.
fun User.canCollect(): Boolean = role in setOf("admin", "sales_manager", "salesman", "accounts", "cashier")

fun User.canSeePayments(): Boolean = role in setOf("admin", "management", "sales_manager", "salesman", "cashier", "accounts")

fun User.canRecordExpenses(): Boolean = role == "admin" || role == "sales_manager" || role == "salesman"

fun User.holdsCash(): Boolean = role in setOf("admin", "sales_manager", "salesman", "accounts")

fun User.canCountCash(): Boolean = role == "admin" || role == "cashier"

fun User.canSeeWallets(): Boolean = role in setOf("admin", "management", "sales_manager", "cashier", "accounts")

// Only admin sets up who holds which wallet.
fun User.canSetUpWallets(): Boolean = role == "admin"

// At the office a transfer is recorded without the customer there, so no signature is asked for.
fun User.isOfficeCollector(): Boolean = role == "admin" || role == "accounts" || role == "cashier"

// Phase 8. Booking a return or making a note changes what a customer owes, so only the sales team
// and Accounts do it; the warehouse sees returns to take the goods back, but never their money.
fun User.canAdjustInvoices(): Boolean = role in setOf("admin", "management", "sales_manager", "accounts")

fun User.canSeeReturns(): Boolean = role in setOf("admin", "management", "sales_manager", "salesman", "cashier", "accounts", "warehouse")

fun User.canReceiveReturns(): Boolean = role == "admin" || role == "warehouse"

fun User.canSeeLedger(): Boolean = role in setOf("admin", "management", "sales_manager", "salesman", "cashier", "accounts")

// Only Accounts and Admin cancel a payment recorded by mistake.
fun User.canCancelPayments(): Boolean = role == "admin" || role == "accounts"
