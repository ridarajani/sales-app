package com.salesapp.di

import com.salesapp.data.InvoiceRepository
import com.salesapp.ui.delivery.ConfirmDeliveryViewModel
import com.salesapp.ui.invoices.InvoiceDetailViewModel
import com.salesapp.ui.invoices.InvoicesViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Phase 6: invoices and the driver's delivery.
val invoicesModule = module {
    single { InvoiceRepository(get(), get()) }
    viewModel { InvoicesViewModel(get(), get()) }
    viewModel { (id: Int) -> InvoiceDetailViewModel(id, get(), get(), get()) }
    viewModel { (planId: Int, customerId: Int) -> ConfirmDeliveryViewModel(planId, customerId, get(), get(), get()) }
}
