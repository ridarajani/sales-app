package com.salesapp.di

import com.salesapp.ui.catalog.CompaniesViewModel
import com.salesapp.ui.catalog.CompanyFormViewModel
import com.salesapp.ui.catalog.ProductFormViewModel
import com.salesapp.ui.catalog.ProductsViewModel
import com.salesapp.ui.catalog.VehicleFormViewModel
import com.salesapp.ui.catalog.VehiclesViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// View models for products, vehicles and company details.
val catalogModule = module {
    viewModel { ProductsViewModel(get(), get()) }
    viewModel { (id: Int?) -> ProductFormViewModel(id, get(), get()) }
    viewModel { VehiclesViewModel(get(), get()) }
    viewModel { (id: Int?) -> VehicleFormViewModel(id, get(), get()) }
    viewModel { CompaniesViewModel(get(), get()) }
    viewModel { (code: String) -> CompanyFormViewModel(code, get(), get()) }
}
