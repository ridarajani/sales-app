package com.salesapp.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.salesapp.ui.catalog.CompaniesPage
import com.salesapp.ui.catalog.CompanyFormPage
import com.salesapp.ui.catalog.ProductFormPage
import com.salesapp.ui.catalog.ProductsPage
import com.salesapp.ui.catalog.VehicleFormPage
import com.salesapp.ui.catalog.VehiclesPage
import com.salesapp.resources.Res
import com.salesapp.resources.coming_next
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.TopBar
import org.jetbrains.compose.resources.stringResource

// Products, vehicles and companies.
fun NavGraphBuilder.catalogGraph(nav: NavController, onExit: () -> Unit) {
    val back = { nav.backOr(onExit) }
    val done = back
    composable<ProductsRoute> {
        ProductsPage(
            onBack = back,
            onOpenProduct = { nav.open(ProductFormRoute(it)) },
            onAddProduct = { nav.open(ProductFormRoute()) },
        )
    }
    composable<ProductFormRoute> { entry ->
        ProductFormPage(id = entry.toRoute<ProductFormRoute>().id, onBack = back, onDone = done)
    }
    composable<VehiclesRoute> {
        VehiclesPage(
            onBack = back,
            onOpenVehicle = { nav.open(VehicleFormRoute(it)) },
            onAddVehicle = { nav.open(VehicleFormRoute()) },
        )
    }
    composable<VehicleFormRoute> { entry ->
        VehicleFormPage(id = entry.toRoute<VehicleFormRoute>().id, onBack = back, onDone = done)
    }
    composable<CompaniesRoute> {
        CompaniesPage(onBack = back, onOpen = { nav.open(CompanyFormRoute(it)) })
    }
    composable<CompanyFormRoute> { entry ->
        CompanyFormPage(code = entry.toRoute<CompanyFormRoute>().code, onBack = back, onDone = done)
    }
}

// A screen that is not built yet.
@Composable
fun ComingNext(title: String, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(title, onBack = onBack)
        EmptyState(stringResource(Res.string.coming_next))
    }
}
