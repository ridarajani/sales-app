package com.salesapp.data

// Where the payment, expense and cash screens get their data.
class CashRepository(private val api: Api, private val app: AppState) {
    private var companies: List<Company>? = null

    init {
        app.onSessionChanged { companies = null }
    }

    suspend fun collectInfo(customerId: Int): CollectInfo = api.collectInfo(customerId)

    suspend fun collect(request: CollectRequest): PaymentDetail = api.collect(request)

    suspend fun payments(query: String?, mode: String?): List<PaymentListItem> =
        api.collections(query?.trim()?.takeIf { it.isNotBlank() }, mode, null)

    suspend fun payment(id: Int): PaymentDetail = api.collection(id)

    suspend fun proof(id: Int): ServerFile = api.collectionProof(id)

    suspend fun signature(id: Int): ServerFile = api.collectionSignature(id)

    suspend fun addExpense(request: ExpenseRequest): Expense = api.addExpense(request)

    suspend fun expenses(): List<Expense> = api.expenses(null)

    suspend fun expensePhoto(id: Int): ServerFile = api.expensePhoto(id)

    suspend fun myCash(): CashHeld = api.myCash()

    suspend fun handOver(): HandoverDetail = api.handOver()

    suspend fun handovers(status: String?): List<HandoverListItem> = api.handovers(status)

    suspend fun handover(id: Int): HandoverDetail = api.handover(id)

    suspend fun count(id: Int, request: CashCountRequest): HandoverDetail = api.countHandover(id, request)

    // The companies this user may pick from, in the order the office uses.
    suspend fun myCompanies(): List<Company> {
        val user = app.user ?: return emptyList()
        val all = companies ?: api.companies().also { companies = it }
        val seesAll = Role.fromCode(user.role)?.seesAllCompanies == true
        return all.filter { seesAll || it.code in user.companies }
    }

    // Customers this user may collect from, for the search on the collect screen.
    suspend fun customers(query: String?): List<Customer> = api.customers(query, null, includeInactive = false)
}
