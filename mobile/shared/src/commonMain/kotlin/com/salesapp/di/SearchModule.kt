package com.salesapp.di

import com.salesapp.data.AlertsRepository
import com.salesapp.data.SearchRepository
import com.salesapp.data.SearchSource
import com.salesapp.ui.alerts.AlertsViewModel
import com.salesapp.ui.search.SearchViewModel
import com.salesapp.ui.trips.OutboxProblemViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Phase 10 part 3: the search box, the alerts, and the trip sales a phone could not read.
val searchModule = module {
    single<SearchSource> { SearchRepository(get()) }
    single { AlertsRepository(get()) }
    viewModel { SearchViewModel(get(), get()) }
    viewModel { AlertsViewModel(get(), get()) }
    viewModel { (id: Int) -> OutboxProblemViewModel(id, get(), get()) }
}
