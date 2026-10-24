package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// Screens for invoices (Phase 6).

// The register: every invoice this user may see.
@Serializable
data object InvoicesRoute

// One invoice, with Print or save and Share on WhatsApp.
@Serializable
data class InvoiceDetailRoute(val id: Int)
