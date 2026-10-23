package com.salesapp.di

import com.salesapp.data.OrdersRepository
import com.salesapp.ui.orders.BookOrderViewModel
import com.salesapp.ui.orders.ChangeOrderViewModel
import com.salesapp.ui.orders.NewOrderViewModel
import com.salesapp.ui.orders.OrderDetailViewModel
import com.salesapp.ui.orders.OrdersViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Data and view models for the order screens.
val ordersModule = module {
    single { OrdersRepository(get(), get()) }
    viewModel { OrdersViewModel(get(), get()) }
    viewModel { NewOrderViewModel(get(), get()) }
    viewModel { (customerId: Int) -> BookOrderViewModel(customerId, get(), get()) }
    viewModel { (id: Int) -> OrderDetailViewModel(id, get(), get()) }
    viewModel { (id: Int) -> ChangeOrderViewModel(id, get(), get()) }
}
