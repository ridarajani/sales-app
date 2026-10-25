package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// Phase 10: the reports the office asked for, and each salesman's monthly target.

@Serializable
data object ReportsRoute

// One report out of the catalogue, by its key.
@Serializable
data class ReportRoute(val key: String)

@Serializable
data object TargetsRoute
