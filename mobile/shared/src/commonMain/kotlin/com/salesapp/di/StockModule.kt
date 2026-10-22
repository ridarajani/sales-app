package com.salesapp.di

import com.salesapp.data.StockRepository
import com.salesapp.ui.stock.AdjustStockViewModel
import com.salesapp.ui.stock.ApprovalsViewModel
import com.salesapp.ui.stock.BatchHistoryViewModel
import com.salesapp.ui.stock.ExpiringViewModel
import com.salesapp.ui.stock.ReceiveStockViewModel
import com.salesapp.ui.stock.StockCountViewModel
import com.salesapp.ui.stock.StockProductViewModel
import com.salesapp.ui.stock.StockViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Data and view models for the stock screens.
val stockModule = module {
    single { StockRepository(get(), get()) }
    viewModel { StockViewModel(get(), get()) }
    viewModel { (productId: Int) -> StockProductViewModel(productId, get(), get()) }
    viewModel { (batchId: Int) -> BatchHistoryViewModel(batchId, get(), get()) }
    viewModel { ExpiringViewModel(get(), get()) }
    viewModel { ApprovalsViewModel(get(), get()) }
    viewModel { ReceiveStockViewModel(get(), get()) }
    viewModel { (batchId: Int) -> AdjustStockViewModel(batchId, get(), get()) }
    viewModel { StockCountViewModel(get(), get()) }
}
