package com.salesapp.ui.stock

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.AdjustStockRoute
import com.salesapp.ui.navigation.ApprovalsRoute
import com.salesapp.ui.navigation.BatchHistoryRoute
import com.salesapp.ui.navigation.ExpiringRoute
import com.salesapp.ui.navigation.ReceiveStockRoute
import com.salesapp.ui.navigation.StockCountRoute
import com.salesapp.ui.navigation.StockProductRoute
import com.salesapp.ui.navigation.StockRoute
import com.salesapp.ui.navigation.open

// The stock list, one product, one batch, receiving, adjusting, counting and expiring soon.
fun NavGraphBuilder.stockGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<StockRoute> {
        StockPage(
            onBack = { back() },
            onOpenProduct = { nav.open(StockProductRoute(it)) },
            onReceive = { nav.open(ReceiveStockRoute) },
            onCount = { nav.open(StockCountRoute) },
        )
    }
    composable<StockProductRoute> { entry ->
        StockProductPage(
            productId = entry.toRoute<StockProductRoute>().productId,
            onBack = { back() },
            onOpenBatch = { nav.open(BatchHistoryRoute(it)) },
        )
    }
    composable<BatchHistoryRoute> { entry ->
        BatchHistoryPage(
            batchId = entry.toRoute<BatchHistoryRoute>().batchId,
            onBack = { back() },
            onAdjust = { nav.open(AdjustStockRoute(it)) },
        )
    }
    composable<ReceiveStockRoute> {
        ReceiveStockPage(onBack = { back() }, onDone = { back() })
    }
    composable<AdjustStockRoute> { entry ->
        AdjustStockPage(batchId = entry.toRoute<AdjustStockRoute>().batchId, onBack = { back() }, onDone = { back() })
    }
    composable<StockCountRoute> {
        StockCountPage(onBack = { back() }, onDone = { back() })
    }
    composable<ExpiringRoute> {
        ExpiringPage(onBack = { back() }, onOpenBatch = { nav.open(BatchHistoryRoute(it)) })
    }
    composable<ApprovalsRoute> {
        ApprovalsPage(onBack = { back() })
    }
}
