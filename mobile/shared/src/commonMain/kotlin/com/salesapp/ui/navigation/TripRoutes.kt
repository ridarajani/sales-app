package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// Screens for Phase 9: minibus routes and upcountry trips.

@Serializable
data object TripsRoute

@Serializable
data class TripDetailRoute(val id: Int)

// Planning a new trip, or changing one that is not loaded yet.
@Serializable
data class PlanTripRoute(val id: Int? = null)

@Serializable
data class TripSaleRoute(val tripId: Int)

@Serializable
data class TripExpenseRoute(val tripId: Int)

@Serializable
data class CloseTripRoute(val tripId: Int)
