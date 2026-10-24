package com.salesapp.di

import com.salesapp.data.AirtelRepository
import com.salesapp.data.CashRepository
import com.salesapp.ui.airtel.WalletDetailViewModel
import com.salesapp.ui.airtel.WalletFormViewModel
import com.salesapp.ui.airtel.WalletsViewModel
import com.salesapp.ui.cash.CollectMoneyViewModel
import com.salesapp.ui.cash.CountCashViewModel
import com.salesapp.ui.cash.ExpensesViewModel
import com.salesapp.ui.cash.HandoverDetailViewModel
import com.salesapp.ui.cash.MyCashViewModel
import com.salesapp.ui.cash.NewExpenseViewModel
import com.salesapp.ui.cash.PaymentDetailViewModel
import com.salesapp.ui.cash.PaymentsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Phase 7: payments, expenses, cash and Airtel Money.
val cashModule = module {
    single { CashRepository(get(), get()) }
    single { AirtelRepository(get(), get()) }
    // All three may be null: the screen is opened empty from Home, or filled in from an invoice.
    viewModel { (customerId: Int?, companyCode: String?, invoiceId: Int?) ->
        CollectMoneyViewModel(customerId, companyCode, invoiceId, get(), get(), get())
    }
    viewModel { PaymentsViewModel(get(), get()) }
    viewModel { (id: Int) -> PaymentDetailViewModel(id, get(), get()) }
    viewModel { ExpensesViewModel(get(), get()) }
    viewModel { NewExpenseViewModel(get(), get(), get()) }
    viewModel { MyCashViewModel(get(), get()) }
    viewModel { CountCashViewModel(get(), get()) }
    viewModel { (id: Int) -> HandoverDetailViewModel(id, get(), get()) }
    viewModel { WalletsViewModel(get(), get()) }
    viewModel { (id: Int) -> WalletDetailViewModel(id, get(), get()) }
    viewModel { (id: Int?) -> WalletFormViewModel(id, get(), get(), get()) }
}
