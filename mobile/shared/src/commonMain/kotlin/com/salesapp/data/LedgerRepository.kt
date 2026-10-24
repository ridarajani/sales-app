package com.salesapp.data

// Where the customer ledger comes from, and the statement PDF.
class LedgerRepository(private val api: Api, private val app: AppState) {
    private var companies: List<Company>? = null

    init {
        app.onSessionChanged { companies = null }
    }

    suspend fun ledger(customerId: Int, company: String, from: String?): Ledger = api.ledger(customerId, company, from, null)

    suspend fun statement(customerId: Int, company: String, from: String?): ServerFile = api.statementPdf(customerId, company, from, null)

    suspend fun customers(query: String?): List<Customer> = api.customers(query, null, includeInactive = false)

    suspend fun customer(id: Int): Customer = api.customer(id)

    // The companies a customer buys from that this user may also see.
    suspend fun companiesOf(customer: Customer): List<Company> {
        val user = app.user ?: return emptyList()
        val all = companies ?: api.companies().also { companies = it }
        val seesAll = Role.fromCode(user.role)?.seesAllCompanies == true
        val theirs = customer.companies.map { it.code }.toSet()
        return all.filter { it.code in theirs && (seesAll || it.code in user.companies) }
    }
}
