package com.salesapp.ui.screens

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.navigation.AddUserRoute
import com.salesapp.ui.navigation.ResetPinRoute
import com.salesapp.ui.navigation.UserDetailRoute
import com.salesapp.ui.navigation.UsersRoute
import com.salesapp.ui.navigation.open

// The people list, adding a person, one person and resetting a PIN.
fun NavGraphBuilder.usersGraph(nav: NavController) {
    val back = { nav.popBackStack() }
    composable<UsersRoute> {
        UsersPage(
            onBack = { back() },
            onOpenUser = { nav.open(UserDetailRoute(it)) },
            onAddUser = { nav.open(AddUserRoute) },
        )
    }
    composable<AddUserRoute> {
        AddUserPage(onBack = { back() }, onDone = { back() })
    }
    composable<UserDetailRoute> { entry ->
        UserDetailPage(
            id = entry.toRoute<UserDetailRoute>().id,
            onBack = { back() },
            onResetPin = { nav.open(ResetPinRoute(it)) },
        )
    }
    composable<ResetPinRoute> { entry ->
        ResetPinPage(id = entry.toRoute<ResetPinRoute>().id, onBack = { back() }, onDone = { back() })
    }
}
