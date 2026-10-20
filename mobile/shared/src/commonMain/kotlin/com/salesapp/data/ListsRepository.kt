package com.salesapp.data

import kotlinx.serialization.json.JsonObject

// Where the list screens get their data. Thin for now; the place to add caching later.
class ListsRepository(private val api: Api) {
    suspend fun customers(q: String?, company: String?, includeInactive: Boolean): List<Customer> =
        api.customers(q, company, includeInactive)

    suspend fun customer(id: Int): Customer = api.customer(id)

    suspend fun createCustomer(request: CustomerRequest): Customer = api.createCustomer(request)

    suspend fun updateCustomer(id: Int, request: CustomerRequest): Customer = api.updateCustomer(id, request)

    suspend fun products(q: String?, company: String?, includeInactive: Boolean): List<Product> =
        api.products(q, company, includeInactive)

    suspend fun product(id: Int): Product = api.product(id)

    suspend fun createProduct(request: ProductRequest): Product = api.createProduct(request)

    suspend fun updateProduct(id: Int, request: ProductRequest): Product = api.updateProduct(id, request)

    suspend fun vehicles(): List<Vehicle> = api.vehicles()

    suspend fun drivers(): List<Driver> = api.drivers()

    suspend fun createVehicle(request: VehicleRequest): Vehicle = api.createVehicle(request)

    suspend fun updateVehicle(id: Int, change: JsonObject): Vehicle = api.updateVehicle(id, change)

    suspend fun companies(): List<Company> = api.companies()

    suspend fun companyDetails(code: String): CompanyDetails = api.companyDetails(code)

    suspend fun updateCompany(code: String, change: JsonObject): CompanyDetails =
        api.updateCompany(code, change)

    suspend fun openingBalances(): List<OpeningBalance> = api.openingBalances()

    suspend fun setOpeningBalance(customerId: Int, request: OpeningBalanceRequest): OpeningBalance =
        api.setOpeningBalance(customerId, request)
}
