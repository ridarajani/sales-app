package com.salesapp.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.currentHour
import com.salesapp.data.Api
import com.salesapp.data.AppState
import com.salesapp.resources.Res
import com.salesapp.resources.home_coming_soon
import com.salesapp.resources.home_good_afternoon
import com.salesapp.resources.home_good_evening
import com.salesapp.resources.home_good_morning
import com.salesapp.resources.tile_airtel
import com.salesapp.resources.tile_airtel_both
import com.salesapp.resources.tile_airtel_ledger
import com.salesapp.resources.tile_approve
import com.salesapp.resources.tile_audit
import com.salesapp.resources.tile_audit_sub
import com.salesapp.resources.tile_balances_terms
import com.salesapp.resources.tile_check_stock
import com.salesapp.resources.tile_collect_money
import com.salesapp.resources.tile_collect_money_sub
import com.salesapp.resources.tile_companies
import com.salesapp.resources.tile_companies_sub
import com.salesapp.resources.tile_count_cash
import com.salesapp.resources.tile_credit_notes
import com.salesapp.resources.tile_credit_notes_sub
import com.salesapp.resources.tile_customers
import com.salesapp.resources.tile_customers_add
import com.salesapp.resources.tile_deliveries
import com.salesapp.resources.tile_hand_over_cash
import com.salesapp.resources.tile_hand_over_cash_sub
import com.salesapp.resources.tile_hand_over_goods
import com.salesapp.resources.tile_hand_over_goods_sub
import com.salesapp.resources.tile_invoices
import com.salesapp.resources.tile_invoices_register
import com.salesapp.resources.tile_invoices_register_print
import com.salesapp.resources.tile_invoices_share
import com.salesapp.resources.tile_jobs
import com.salesapp.resources.tile_ledger
import com.salesapp.resources.tile_ledger_sub
import com.salesapp.resources.tile_load_van
import com.salesapp.resources.tile_loading_plan
import com.salesapp.resources.tile_loading_plans
import com.salesapp.resources.tile_look_up
import com.salesapp.resources.tile_more
import com.salesapp.resources.tile_more_sub
import com.salesapp.resources.tile_my_customers
import com.salesapp.resources.tile_my_orders
import com.salesapp.resources.tile_my_orders_sub
import com.salesapp.resources.tile_my_salesmen
import com.salesapp.resources.tile_my_salesmen_sub
import com.salesapp.resources.tile_new_order
import com.salesapp.resources.tile_new_order_sub
import com.salesapp.resources.tile_nothing_waiting
import com.salesapp.resources.tile_products
import com.salesapp.resources.tile_products_sub
import com.salesapp.resources.tile_receive_stock
import com.salesapp.resources.tile_receive_stock_sub
import com.salesapp.resources.tile_record_payment
import com.salesapp.resources.tile_record_payment_sub
import com.salesapp.resources.tile_report_damage
import com.salesapp.resources.tile_report_damage_sub
import com.salesapp.resources.tile_report_shortage
import com.salesapp.resources.tile_report_shortage_sub
import com.salesapp.resources.tile_reports
import com.salesapp.resources.tile_reports_export_sub
import com.salesapp.resources.tile_reports_sales_sub
import com.salesapp.resources.tile_reports_stock_sub
import com.salesapp.resources.tile_stock
import com.salesapp.resources.tile_stock_sub
import com.salesapp.resources.tile_today
import com.salesapp.resources.tile_today_sub
import com.salesapp.resources.tile_users
import com.salesapp.resources.tile_users_admin_sub
import com.salesapp.resources.tile_users_manage_sub
import com.salesapp.resources.tile_vehicles
import com.salesapp.resources.tile_vehicles_sub
import com.salesapp.resources.tile_who_owes
import com.salesapp.ui.components.AppIcons
import com.salesapp.ui.navigation.CompaniesRoute
import com.salesapp.ui.navigation.CustomersRoute
import com.salesapp.ui.navigation.ProductsRoute
import com.salesapp.ui.navigation.UsersRoute
import com.salesapp.ui.navigation.VehiclesRoute
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

// One square on the home screen. If it has no screen yet, `opens` is null.
class TileDef(
    val labelRes: StringResource,
    val subRes: StringResource,
    val icon: ImageVector,
    val opens: Any? = null,
)

private fun tile(label: StringResource, sub: StringResource, icon: ImageVector, opens: Any? = null) =
    TileDef(label, sub, icon, opens)

private fun more() = tile(Res.string.tile_more, Res.string.tile_more_sub, AppIcons.Grid)

// The squares each kind of person sees, in order. The first one is highlighted.
internal fun tilesFor(role: String): List<TileDef> = when (role) {
    "admin" -> listOf(
        tile(Res.string.tile_users, Res.string.tile_users_admin_sub, AppIcons.Person, UsersRoute),
        tile(Res.string.tile_customers, Res.string.tile_customers_add, AppIcons.People, CustomersRoute),
        tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
        tile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, VehiclesRoute),
        tile(Res.string.tile_companies, Res.string.tile_companies_sub, AppIcons.Settings, CompaniesRoute),
        tile(Res.string.tile_audit, Res.string.tile_audit_sub, AppIcons.List),
        tile(Res.string.tile_reports, Res.string.tile_reports_sales_sub, AppIcons.Chart),
        more(),
    )
    "management" -> listOf(
        tile(Res.string.tile_today, Res.string.tile_today_sub, AppIcons.Chart),
        tile(Res.string.tile_approve, Res.string.tile_nothing_waiting, AppIcons.Inbox),
        tile(Res.string.tile_loading_plans, Res.string.tile_nothing_waiting, AppIcons.Truck),
        tile(Res.string.tile_airtel, Res.string.tile_airtel_both, AppIcons.Money),
        tile(Res.string.tile_reports, Res.string.tile_reports_export_sub, AppIcons.Chart),
        tile(Res.string.tile_stock, Res.string.tile_stock_sub, AppIcons.Box),
        tile(Res.string.tile_invoices, Res.string.tile_invoices_register, AppIcons.Receipt),
        tile(Res.string.tile_customers, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
        tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
        tile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, VehiclesRoute),
        tile(Res.string.tile_users, Res.string.tile_users_manage_sub, AppIcons.Person, UsersRoute),
        more(),
    )
    "sales_manager" -> listOf(
        tile(Res.string.tile_new_order, Res.string.tile_new_order_sub, AppIcons.Cart),
        tile(Res.string.tile_loading_plan, Res.string.tile_nothing_waiting, AppIcons.Truck),
        tile(Res.string.tile_collect_money, Res.string.tile_collect_money_sub, AppIcons.Money),
        tile(Res.string.tile_airtel, Res.string.tile_airtel_ledger, AppIcons.Money),
        tile(Res.string.tile_hand_over_cash, Res.string.tile_hand_over_cash_sub, AppIcons.Money),
        tile(Res.string.tile_my_salesmen, Res.string.tile_my_salesmen_sub, AppIcons.People, UsersRoute),
        tile(Res.string.tile_my_orders, Res.string.tile_my_orders_sub, AppIcons.Cart),
        tile(Res.string.tile_my_customers, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
        tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
        tile(Res.string.tile_invoices, Res.string.tile_invoices_share, AppIcons.Receipt),
        tile(Res.string.tile_reports, Res.string.tile_reports_sales_sub, AppIcons.Chart),
        more(),
    )
    "salesman" -> listOf(
        tile(Res.string.tile_new_order, Res.string.tile_new_order_sub, AppIcons.Cart),
        tile(Res.string.tile_collect_money, Res.string.tile_collect_money_sub, AppIcons.Money),
        tile(Res.string.tile_hand_over_cash, Res.string.tile_hand_over_cash_sub, AppIcons.Money),
        tile(Res.string.tile_my_orders, Res.string.tile_my_orders_sub, AppIcons.Cart),
        tile(Res.string.tile_my_customers, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
        more(),
    )
    "warehouse" -> listOf(
        tile(Res.string.tile_jobs, Res.string.tile_nothing_waiting, AppIcons.Box),
        tile(Res.string.tile_load_van, Res.string.tile_nothing_waiting, AppIcons.Truck),
        tile(Res.string.tile_receive_stock, Res.string.tile_receive_stock_sub, AppIcons.Box),
        tile(Res.string.tile_check_stock, Res.string.tile_stock_sub, AppIcons.Box),
        tile(Res.string.tile_report_damage, Res.string.tile_report_damage_sub, AppIcons.Inbox),
        tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
        tile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, VehiclesRoute),
        tile(Res.string.tile_reports, Res.string.tile_reports_stock_sub, AppIcons.Chart),
        more(),
    )
    "driver" -> listOf(
        tile(Res.string.tile_deliveries, Res.string.tile_nothing_waiting, AppIcons.Truck),
        tile(Res.string.tile_hand_over_goods, Res.string.tile_hand_over_goods_sub, AppIcons.Box),
        tile(Res.string.tile_report_shortage, Res.string.tile_report_shortage_sub, AppIcons.Inbox),
        more(),
    )
    "cashier" -> listOf(
        tile(Res.string.tile_count_cash, Res.string.tile_nothing_waiting, AppIcons.Money),
        tile(Res.string.tile_record_payment, Res.string.tile_record_payment_sub, AppIcons.Money),
        tile(Res.string.tile_who_owes, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
        tile(Res.string.tile_invoices, Res.string.tile_invoices_register_print, AppIcons.Receipt),
        tile(Res.string.tile_reports, Res.string.tile_reports_export_sub, AppIcons.Chart),
        more(),
    )
    "accounts" -> listOf(
        tile(Res.string.tile_invoices, Res.string.tile_invoices_register_print, AppIcons.Receipt),
        tile(Res.string.tile_credit_notes, Res.string.tile_credit_notes_sub, AppIcons.Receipt),
        tile(Res.string.tile_ledger, Res.string.tile_ledger_sub, AppIcons.List),
        tile(Res.string.tile_customers, Res.string.tile_balances_terms, AppIcons.People, CustomersRoute),
        tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
        tile(Res.string.tile_reports, Res.string.tile_reports_export_sub, AppIcons.Chart),
        more(),
    )
    else -> listOf(more())
}


private fun greetingFor(hour: Int): StringResource = when {
    hour < 12 -> Res.string.home_good_morning
    hour < 17 -> Res.string.home_good_afternoon
    else -> Res.string.home_good_evening
}

data class HomeState(
    val name: String = "",
    val firstName: String = "",
    val role: String = "",
    val greeting: StringResource = Res.string.home_good_morning,
    val tiles: List<TileDef> = emptyList(),
    val sheetOpen: Boolean = false,
)

sealed interface HomeEvent {
    data class OpenTile(val tile: TileDef) : HomeEvent
    data object ShowAccount : HomeEvent
    data object HideAccount : HomeEvent
    data object SignOut : HomeEvent
    data object Resumed : HomeEvent
}

sealed interface HomeEffect {
    data class Open(val route: Any) : HomeEffect
}

class HomeViewModel(private val app: AppState) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<HomeEffect> = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            app.session.user.collect { user ->
                _state.update {
                    it.copy(
                        name = user?.name ?: "",
                        firstName = user?.name?.trim()?.substringBefore(' ') ?: "",
                        role = user?.role ?: "",
                        tiles = if (user != null) tilesFor(user.role) else emptyList(),
                    )
                }
            }
        }
    }

    private fun initialState(): HomeState {
        val user = app.user ?: return HomeState(greeting = greetingFor(currentHour()))
        return HomeState(
            name = user.name,
            firstName = user.name.trim().substringBefore(' '),
            role = user.role,
            greeting = greetingFor(currentHour()),
            tiles = tilesFor(user.role),
        )
    }

    fun onEvent(event: HomeEvent) {
        when (event) {
            is HomeEvent.OpenTile -> {
                val target = event.tile.opens
                if (target != null) _effects.tryEmit(HomeEffect.Open(target)) else comingSoon()
            }
            HomeEvent.ShowAccount -> _state.update { it.copy(sheetOpen = true) }
            HomeEvent.HideAccount -> _state.update { it.copy(sheetOpen = false) }
            HomeEvent.SignOut -> signOut()
            HomeEvent.Resumed -> _state.update { it.copy(greeting = greetingFor(currentHour())) }
        }
    }

    private fun comingSoon() {
        viewModelScope.launch { app.message = getString(Res.string.home_coming_soon) }
    }

    private fun signOut() {
        _state.update { it.copy(sheetOpen = false) }
        // Sign out on the phone at once; tell the server in the background.
        val token = app.session.token
        val oldApi = Api({ app.apiUrl }) { token }
        app.signedOut()
        app.backgroundScope.launch {
            try {
                oldApi.logout()
            } catch (e: Exception) {
                // Signing out on the phone works even if the server cannot be reached.
            } finally {
                oldApi.close()
            }
        }
    }
}
