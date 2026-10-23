package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// Screens for sales orders.

@Serializable
data object OrdersRoute

// Pick the customer for a new order.
@Serializable
data object NewOrderRoute

@Serializable
data class BookOrderRoute(val customerId: Int)

@Serializable
data class OrderDetailRoute(val id: Int)

@Serializable
data class ChangeOrderRoute(val id: Int)
