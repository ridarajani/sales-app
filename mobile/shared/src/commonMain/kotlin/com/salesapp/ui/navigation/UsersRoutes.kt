package com.salesapp.ui.navigation

import kotlinx.serialization.Serializable

// Screens for managing the people who can sign in.

@Serializable
data object UsersRoute

@Serializable
data object AddUserRoute

@Serializable
data class UserDetailRoute(val id: Int)

@Serializable
data class EditUserRoute(val id: Int)

@Serializable
data class ResetPinRoute(val id: Int)
