package com.salesapp.di

import com.salesapp.data.LoadingRepository
import com.salesapp.ui.loading.NewPlanViewModel
import com.salesapp.ui.loading.PlannerPlanViewModel
import com.salesapp.ui.loading.PlansViewModel
import com.salesapp.ui.loading.TripViewModel
import com.salesapp.ui.loading.WarehousePlanViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Data and view models for the loading plan screens.
val loadingModule = module {
    single { LoadingRepository(get(), get()) }
    viewModel { PlansViewModel(get(), get()) }
    viewModel { NewPlanViewModel(get(), get()) }
    viewModel { (id: Int) -> PlannerPlanViewModel(id, get(), get()) }
    viewModel { (id: Int) -> WarehousePlanViewModel(id, get(), get(), get()) }
    viewModel { (id: Int) -> TripViewModel(id, get(), get(), get()) }
}
