package com.salesapp.di

import com.salesapp.core.KeyValueStore
import com.salesapp.data.Api
import com.salesapp.data.AppState
import com.salesapp.data.ListsRepository
import com.salesapp.data.SessionStore
import org.koin.dsl.module

// Everything Koin can hand out. Screen view models live in customersModule and catalogModule.
val appModule = module {
    single { KeyValueStore() }
    single { SessionStore(get()) }
    single { AppState(get(), get()) }
    // The one Api for the whole app lives in AppState: it reads the address and token each time.
    single<Api> { get<AppState>().api }
    single { ListsRepository(get()) }
    includes(phase1Module, usersModule, customersModule, catalogModule, stockModule, ordersModule, loadingModule)
}
