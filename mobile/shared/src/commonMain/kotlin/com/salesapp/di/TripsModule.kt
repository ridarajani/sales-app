package com.salesapp.di

import com.salesapp.core.FileStore
import com.salesapp.data.TripOutboxStore
import com.salesapp.data.TripsRepository
import com.salesapp.ui.trips.CloseTripViewModel
import com.salesapp.ui.trips.PlanTripViewModel
import com.salesapp.ui.trips.TripDetailViewModel
import com.salesapp.ui.trips.TripExpenseViewModel
import com.salesapp.ui.trips.TripSaleViewModel
import com.salesapp.ui.trips.TripsViewModel
import org.koin.core.module.dsl.viewModel
import org.koin.dsl.module

// Phase 9: trips, and the outbox that lets the leader sell with no network.
val tripsModule = module {
    single { FileStore() }
    single { TripOutboxStore(get()) }
    single { TripsRepository(get(), get(), get()) }
    viewModel { TripsViewModel(get(), get()) }
    viewModel { (id: Int) -> TripDetailViewModel(id, get(), get()) }
    // Null when a new trip is being planned.
    viewModel { (id: Int?) -> PlanTripViewModel(id, get(), get()) }
    // orderId is null for an ordinary sale at a stop, set when an order is being delivered.
    viewModel { (tripId: Int, orderId: Int?) -> TripSaleViewModel(tripId, orderId, get(), get(), get()) }
    viewModel { (tripId: Int) -> TripExpenseViewModel(tripId, get(), get(), get()) }
    viewModel { (tripId: Int) -> CloseTripViewModel(tripId, get(), get()) }
}
