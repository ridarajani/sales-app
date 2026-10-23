package com.salesapp.di

import com.salesapp.ui.screens.HomeViewModel
import com.salesapp.ui.screens.PhoneViewModel
import com.salesapp.ui.screens.PinViewModel
import com.salesapp.ui.screens.ServerAddressViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// View models for sign in, the server address and Home.
val phase1Module = module {
    viewModel { PhoneViewModel(get()) }
    viewModel { (phone: String, name: String) -> PinViewModel(phone, name, get()) }
    viewModel { ServerAddressViewModel(get()) }
    viewModel { HomeViewModel(get(), get(), get()) }
}
