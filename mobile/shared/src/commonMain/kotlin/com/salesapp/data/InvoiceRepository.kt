package com.salesapp.data

// Where the invoice screens get their data, and where the PDFs come from.
class InvoiceRepository(private val api: Api, private val app: AppState) {
    private var companies: List<Company>? = null

    init {
        app.onSessionChanged { companies = null }
    }

    // query searches the number, the shop and the LPO; company is a code like LV; type is vat or plain.
    suspend fun list(query: String?, company: String?, type: String?): List<InvoiceListItem> =
        api.invoices(query?.trim()?.takeIf { it.isNotBlank() }, company, type)

    suspend fun invoice(id: Int): InvoiceDetail = api.invoice(id)

    suspend fun pdf(id: Int): ServerFile = api.invoicePdf(id)

    // The companies this user may see, in code order, for the chips on the register.
    suspend fun myCompanies(): List<Company> {
        val user = app.user ?: return emptyList()
        val all = companies ?: api.companies().also { companies = it }
        val seesAll = Role.fromCode(user.role)?.seesAllCompanies == true
        return all.filter { seesAll || it.code in user.companies }.sortedBy { it.code }
    }
}
