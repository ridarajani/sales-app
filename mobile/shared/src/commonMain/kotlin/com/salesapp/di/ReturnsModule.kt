package com.salesapp.di

import com.salesapp.data.LedgerRepository
import com.salesapp.data.ReturnsRepository
import com.salesapp.ui.ledger.LedgerViewModel
import com.salesapp.ui.returns.NewNoteViewModel
import com.salesapp.ui.returns.NewReturnViewModel
import com.salesapp.ui.returns.ReturnDetailViewModel
import com.salesapp.ui.returns.ReturnsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Phase 8: sales returns, credit and debit notes, and the customer ledger.
val returnsModule = module {
    single { ReturnsRepository(get(), get()) }
    single { LedgerRepository(get(), get()) }
    viewModel { ReturnsViewModel(get(), get()) }
    viewModel { (id: Int) -> ReturnDetailViewModel(id, get(), get()) }
    viewModel { (invoiceId: Int) -> NewReturnViewModel(invoiceId, get(), get(), get()) }
    viewModel { (invoiceId: Int) -> NewNoteViewModel(invoiceId, get(), get(), get()) }
    // Both may be null: the ledger is opened empty from Home, or filled in from an invoice.
    viewModel { (customerId: Int?, companyCode: String?) -> LedgerViewModel(customerId, companyCode, get(), get(), get()) }
}
