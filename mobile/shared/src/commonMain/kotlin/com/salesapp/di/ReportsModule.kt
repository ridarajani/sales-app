package com.salesapp.di

import com.salesapp.data.ReportsRepository
import com.salesapp.ui.reports.ReportViewModel
import com.salesapp.ui.reports.ReportsViewModel
import com.salesapp.ui.reports.TargetsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Phase 10: reports and targets. The repository keeps the catalogue and the filters each report was
// last run with, so it lives as long as the app does.
val reportsModule = module {
    single { ReportsRepository(get(), get()) }
    viewModel { ReportsViewModel(get(), get()) }
    viewModel { (key: String) -> ReportViewModel(key, get(), get(), get(), get()) }
    viewModel { TargetsViewModel(get(), get()) }
}
