package com.salesapp.di

import com.salesapp.data.UsersRepository
import com.salesapp.ui.screens.AddUserViewModel
import com.salesapp.ui.screens.ResetPinViewModel
import com.salesapp.ui.screens.UserDetailViewModel
import com.salesapp.ui.screens.UsersViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// View models for the user screens.
val usersModule = module {
    single { UsersRepository(get(), get()) }
    viewModel { UsersViewModel(get(), get()) }
    viewModel { AddUserViewModel(get(), get()) }
    viewModel { (id: Int) -> UserDetailViewModel(id, get(), get()) }
    viewModel { (id: Int) -> ResetPinViewModel(id, get(), get()) }
}
