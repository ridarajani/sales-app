package com.salesapp.data

// Where the loading plan screens get their data. Thin: every screen reloads on resume.
class LoadingRepository(private val api: Api, private val app: AppState) {
    private var companies: List<Company>? = null

    init {
        app.onSessionChanged { companies = null }
    }

    // status: planned, loaded, out or done; null for all.
    suspend fun plans(status: String?): List<LoadingPlanListItem> = api.loadingPlans(status, null)

    suspend fun plan(id: Int): PlanDetail = api.loadingPlan(id)

    suspend fun create(facilityCode: String): PlanDetail = api.createPlan(NewPlanRequest(facilityCode = facilityCode))

    suspend fun openLines(id: Int): List<OpenProduct> = api.openLines(id)

    suspend fun saveItems(id: Int, request: PlanItemsRequest): PlanDetail = api.savePlanItems(id, request)

    suspend fun send(id: Int): PlanDetail = api.sendPlan(id)

    suspend fun warehouseChange(id: Int, request: WarehouseChangeRequest): PlanDetail = api.warehouseChange(id, request)

    suspend fun load(id: Int): PlanDetail = api.loadPlan(id)

    suspend fun gate(id: Int): PlanDetail = api.gatePlan(id)

    suspend fun seen(id: Int): PlanDetail = api.seenPlan(id)

    suspend fun delete(id: Int) = api.deletePlan(id)

    // Active vehicles that may load at this warehouse (no warehouse set, or this one).
    suspend fun vehicles(facilityCode: String): List<Vehicle> =
        api.vehicles().filter { it.active && (it.facilityCode == null || it.facilityCode == facilityCode) }

    // Active drivers only (the server leaves the others out).
    suspend fun drivers(): List<Driver> = api.drivers()

    // The warehouses this user has a company in, in code order (F1, F2): the facility code paired with
    // the names of the user's companies there. Screens show the code with facilityLabel(code).
    suspend fun myFacilities(): List<Pair<String, String>> {
        val user = app.user ?: return emptyList()
        val all = companies ?: api.companies().also { companies = it }
        val seesAll = Role.fromCode(user.role)?.seesAllCompanies == true
        return all
            .filter { it.facilityCode.isNotBlank() && (seesAll || it.code in user.companies) }
            .groupBy { it.facilityCode }
            .map { (facility, list) -> facility to list.joinToString(", ") { it.name } }
            .sortedBy { it.first }
    }

    // How many changes from the other side wait to be seen, over all plans this user can see.
    suspend fun newChangesTotal(): Int = plans(null).sumOf { it.newChanges }

    // Phase 6.

    // Makes the invoices for this user's companies. The plan comes back with `made` filled in.
    suspend fun makeInvoices(id: Int): PlanDetail = api.makeInvoices(id)

    suspend fun confirmDelivery(id: Int, request: DeliveryRequest): PlanDetail = api.confirmDelivery(id, request)

    suspend fun deliveryNote(id: Int): ServerFile = api.deliveryNotePdf(id)

    suspend fun gatePass(id: Int): ServerFile = api.gatePassPdf(id)

    suspend fun signature(deliveryId: Int): ServerFile = api.deliverySignature(deliveryId)

    suspend fun photo(deliveryId: Int): ServerFile = api.deliveryPhoto(deliveryId)
}
