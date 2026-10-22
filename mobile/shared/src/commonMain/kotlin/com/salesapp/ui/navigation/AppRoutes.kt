package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// The sign-in screens, Home and the server address screen.

@Serializable
data object PhoneRoute

@Serializable
data class PinRoute(val phone: String, val name: String)

@Serializable
data object HomeRoute

@Serializable
data object ServerAddressRoute
