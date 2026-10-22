package com.salesapp.di

import com.salesapp.data.StockRepository
import org.koin.dsl.module

// Data for the stock screens.
val stockModule = module {
    single { StockRepository(get(), get()) }
}
