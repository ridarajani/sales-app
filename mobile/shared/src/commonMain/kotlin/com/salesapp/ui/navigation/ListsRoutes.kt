package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// Screens inside the lists area (customers, products, vehicles, companies).

@Serializable
data object CustomersRoute

@Serializable
data class CustomerDetailRoute(val id: Int)

@Serializable
data class CustomerFormRoute(val id: Int? = null)

@Serializable
data class OpeningBalanceRoute(val customerId: Int, val companyCode: String)

@Serializable
data object ProductsRoute

@Serializable
data class ProductFormRoute(val id: Int? = null)

@Serializable
data object VehiclesRoute

@Serializable
data class VehicleFormRoute(val id: Int? = null)

@Serializable
data object CompaniesRoute

@Serializable
data class CompanyFormRoute(val code: String)
