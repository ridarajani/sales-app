package com.salesapp.di

import com.salesapp.ui.customers.CustomerDetailViewModel
import com.salesapp.ui.customers.CustomerFormViewModel
import com.salesapp.ui.customers.CustomersViewModel
import com.salesapp.ui.customers.OpeningBalanceViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// View models for the customer screens (list, detail, form, opening balance).
val customersModule = module {
    viewModel { CustomersViewModel(get(), get()) }
    viewModel { (id: Int) -> CustomerDetailViewModel(id, get(), get()) }
    viewModel { (id: Int?) -> CustomerFormViewModel(id, get(), get()) }
    viewModel { (customerId: Int, companyCode: String) -> OpeningBalanceViewModel(customerId, companyCode, get(), get()) }
}
