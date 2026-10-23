package com.salesapp.data

import com.salesapp.core.DEFAULT_VAT_RATE

// Where the order screens get their data. Keeps VAT rates per company so booking does not ask each time.
class OrdersRepository(private val api: Api, private val app: AppState) {
    private val vatRates = mutableMapOf<String, Double>()

    init {
        app.onSessionChanged { vatRates.clear() }
    }

    // status: open, hold, done or cancelled; null for all. q matches the order number or customer name.
    suspend fun orders(status: String?, q: String?, company: String? = null): List<OrderListItem> =
        api.orders(status, q, company)

    suspend fun booking(customerId: Int): BookingInfo = api.booking(customerId)

    // The active products a company sells.
    suspend fun products(company: String): List<Product> = api.products(null, company, false).filter { it.active }

    // The company's VAT rate in percent; 17.5 when none is set.
    suspend fun vatRate(company: String): Double {
        vatRates[company]?.let { return it }
        val rate = api.companyDetails(company).vatRate ?: DEFAULT_VAT_RATE
        vatRates[company] = rate
        return rate
    }

    // Active salesmen who sell for every one of these companies. Empty when this user may not list users.
    suspend fun salesmenFor(companies: Set<String>): List<UserRow> {
        val role = app.user?.role
        if (role != "admin" && role != "management" && role != "sales_manager") return emptyList()
        val users = try {
            api.users()
        } catch (e: ApiException) {
            if (e.status == 403) return emptyList() else throw e
        }
        return users
            .filter { it.active && it.role == "salesman" && it.companies.containsAll(companies) }
            .sortedBy { it.name.lowercase() }
    }

    // Active customers, for picking who the order is for.
    suspend fun customers(q: String?): List<Customer> = api.customers(q, null, false).filter { it.active }

    suspend fun book(request: NewOrderRequest): OrderDetail = api.createOrder(request)

    suspend fun order(id: Int): OrderDetail = api.order(id)

    suspend fun change(id: Int, change: OrderChange): OrderDetail = api.changeOrder(id, change)

    suspend fun reserve(id: Int): OrderDetail = api.reserveOrder(id)

    suspend fun hold(id: Int): OrderDetail = api.holdOrder(id)

    suspend fun release(id: Int): OrderDetail = api.releaseOrder(id)

    suspend fun close(id: Int): OrderDetail = api.closeOrder(id)

    suspend fun cancel(id: Int): OrderDetail = api.cancelOrder(id)
}
