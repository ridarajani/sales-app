package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// Screens for stock in the warehouse.

@Serializable
data object StockRoute

@Serializable
data class StockProductRoute(val productId: Int)

@Serializable
data class BatchHistoryRoute(val batchId: Int)

@Serializable
data object ReceiveStockRoute

@Serializable
data class AdjustStockRoute(val batchId: Int)

@Serializable
data object StockCountRoute

@Serializable
data object ExpiringRoute

@Serializable
data object ApprovalsRoute
