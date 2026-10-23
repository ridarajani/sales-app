package com.salesapp.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.currentHour
import com.salesapp.data.Api
import com.salesapp.data.AppState
import com.salesapp.data.PendingAdjustment
import com.salesapp.data.ShortDatedBatch
import com.salesapp.data.StockRepository
import com.salesapp.data.canApproveStock
import com.salesapp.data.canSeeStock
import com.salesapp.resources.Res
import com.salesapp.resources.home_coming_soon
import com.salesapp.resources.home_good_afternoon
import com.salesapp.resources.home_good_evening
import com.salesapp.resources.home_good_morning
import com.salesapp.resources.tile_airtel
import com.salesapp.resources.tile_airtel_both
import com.salesapp.resources.tile_airtel_ledger
import com.salesapp.resources.tile_approvals
import com.salesapp.resources.tile_approvals_count
import com.salesapp.resources.tile_approvals_one
import com.salesapp.resources.tile_audit
import com.salesapp.resources.tile_audit_sub
import com.salesapp.resources.tile_balances_terms
import com.salesapp.resources.tile_collect_money
import com.salesapp.resources.tile_collect_money_sub
import com.salesapp.resources.tile_companies
import com.salesapp.resources.tile_companies_sub
import com.salesapp.resources.tile_count_cash
import com.salesapp.resources.tile_credit_notes
import com.salesapp.resources.tile_credit_notes_sub
import com.salesapp.resources.tile_customers
import com.salesapp.resources.tile_customers_add
import com.salesapp.resources.tile_expiring
import com.salesapp.resources.tile_expiring_count
import com.salesapp.resources.tile_expiring_mixed
import com.salesapp.resources.tile_expiring_loading
import com.salesapp.resources.tile_expiring_none
import com.salesapp.resources.tile_expiring_one
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
import com.salesapp.resources.tile_my_salesmen
import com.salesapp.resources.tile_my_salesmen_sub
import com.salesapp.resources.tile_new_order
import com.salesapp.resources.tile_new_order_sub
import com.salesapp.resources.tile_approvals_loading
import com.salesapp.resources.tile_nothing_waiting
import com.salesapp.resources.tile_orders
import com.salesapp.resources.tile_orders_sub
import com.salesapp.resources.tile_products
import com.salesapp.resources.tile_products_sub
import com.salesapp.resources.tile_receive_stock
import com.salesapp.resources.tile_receive_stock_sub
import com.salesapp.resources.tile_record_payment
import com.salesapp.resources.tile_record_payment_sub
import com.salesapp.resources.tile_report_shortage
import com.salesapp.resources.tile_report_shortage_sub
import com.salesapp.resources.tile_reports
import com.salesapp.resources.tile_reports_export_sub
import com.salesapp.resources.tile_reports_sales_sub
import com.salesapp.resources.tile_reports_stock_sub
import com.salesapp.resources.tile_stock
import com.salesapp.resources.tile_stock_count
import com.salesapp.resources.tile_stock_count_sub
import com.salesapp.resources.tile_stock_sub
import com.salesapp.resources.tile_stock_warehouse_sub
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
import com.salesapp.ui.navigation.ApprovalsRoute
import com.salesapp.ui.navigation.ExpiringRoute
import com.salesapp.ui.navigation.NewOrderRoute
import com.salesapp.ui.navigation.OrdersRoute
import com.salesapp.ui.navigation.ProductsRoute
import com.salesapp.ui.navigation.ReceiveStockRoute
import com.salesapp.ui.navigation.StockCountRoute
import com.salesapp.ui.navigation.StockRoute
import com.salesapp.ui.navigation.UsersRoute
import com.salesapp.ui.navigation.VehiclesRoute
import kotlinx.coroutines.CancellationException
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
    // A sub-line worked out at run time; the screen shows it instead of subRes when it is set.
    val subText: String? = null,
)

private fun tile(label: StringResource, sub: StringResource, icon: ImageVector, opens: Any? = null, subText: String? = null) =
    TileDef(label, sub, icon, opens, subText)

private fun expiringTile(subText: String?) =
    tile(Res.string.tile_expiring, Res.string.tile_expiring_loading, AppIcons.Inbox, ExpiringRoute, subText)

private fun approvalsTile(subText: String?) =
    tile(Res.string.tile_approvals, Res.string.tile_approvals_loading, AppIcons.Inbox, ApprovalsRoute, subText)

private fun more() = tile(Res.string.tile_more, Res.string.tile_more_sub, AppIcons.Grid)

private fun newOrderTile() = tile(Res.string.tile_new_order, Res.string.tile_new_order_sub, AppIcons.Cart, NewOrderRoute)

private fun ordersTile() = tile(Res.string.tile_orders, Res.string.tile_orders_sub, AppIcons.Receipt, OrdersRoute)

// The squares each kind of person sees, in order. The first one is highlighted.
// Only admin, management and warehouse get stock squares (the same roles as canSeeStock); no other role lists them.
// Everyone but drivers gets Orders (canSeeOrders); those who book (canBookOrders) start with New order.
internal fun tilesFor(role: String, expiringSub: String? = null, approvalsSub: String? = null): List<TileDef> = when (role) {
    "admin" -> listOf(
        newOrderTile(),
        tile(Res.string.tile_users, Res.string.tile_users_admin_sub, AppIcons.Person, UsersRoute),
        tile(Res.string.tile_customers, Res.string.tile_customers_add, AppIcons.People, CustomersRoute),
        ordersTile(),
        tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
        tile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, VehiclesRoute),
        tile(Res.string.tile_companies, Res.string.tile_companies_sub, AppIcons.Settings, CompaniesRoute),
        tile(Res.string.tile_stock, Res.string.tile_stock_warehouse_sub, AppIcons.Box, StockRoute),
        expiringTile(expiringSub),
        approvalsTile(approvalsSub),
        tile(Res.string.tile_audit, Res.string.tile_audit_sub, AppIcons.List),
        tile(Res.string.tile_reports, Res.string.tile_reports_sales_sub, AppIcons.Chart),
        more(),
    )
    "management" -> listOf(
        tile(Res.string.tile_today, Res.string.tile_today_sub, AppIcons.Chart),
        ordersTile(),
        approvalsTile(approvalsSub),
        tile(Res.string.tile_loading_plans, Res.string.tile_nothing_waiting, AppIcons.Truck),
        tile(Res.string.tile_airtel, Res.string.tile_airtel_both, AppIcons.Money),
        tile(Res.string.tile_reports, Res.string.tile_reports_export_sub, AppIcons.Chart),
        tile(Res.string.tile_stock, Res.string.tile_stock_sub, AppIcons.Box, StockRoute),
        expiringTile(expiringSub),
        tile(Res.string.tile_invoices, Res.string.tile_invoices_register, AppIcons.Receipt),
        tile(Res.string.tile_customers, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
        tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
        tile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, VehiclesRoute),
        tile(Res.string.tile_users, Res.string.tile_users_manage_sub, AppIcons.Person, UsersRoute),
        more(),
    )
    "sales_manager" -> listOf(
        newOrderTile(),
        tile(Res.string.tile_loading_plan, Res.string.tile_nothing_waiting, AppIcons.Truck),
        tile(Res.string.tile_collect_money, Res.string.tile_collect_money_sub, AppIcons.Money),
        tile(Res.string.tile_airtel, Res.string.tile_airtel_ledger, AppIcons.Money),
        tile(Res.string.tile_hand_over_cash, Res.string.tile_hand_over_cash_sub, AppIcons.Money),
        tile(Res.string.tile_my_salesmen, Res.string.tile_my_salesmen_sub, AppIcons.People, UsersRoute),
        ordersTile(),
        tile(Res.string.tile_my_customers, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
        tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
        tile(Res.string.tile_invoices, Res.string.tile_invoices_share, AppIcons.Receipt),
        tile(Res.string.tile_reports, Res.string.tile_reports_sales_sub, AppIcons.Chart),
        more(),
    )
    "salesman" -> listOf(
        newOrderTile(),
        tile(Res.string.tile_collect_money, Res.string.tile_collect_money_sub, AppIcons.Money),
        tile(Res.string.tile_hand_over_cash, Res.string.tile_hand_over_cash_sub, AppIcons.Money),
        ordersTile(),
        tile(Res.string.tile_my_customers, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
        more(),
    )
    "warehouse" -> listOf(
        tile(Res.string.tile_jobs, Res.string.tile_nothing_waiting, AppIcons.Box),
        ordersTile(),
        tile(Res.string.tile_load_van, Res.string.tile_nothing_waiting, AppIcons.Truck),
        tile(Res.string.tile_receive_stock, Res.string.tile_receive_stock_sub, AppIcons.Box, ReceiveStockRoute),
        tile(Res.string.tile_stock, Res.string.tile_stock_warehouse_sub, AppIcons.Box, StockRoute),
        expiringTile(expiringSub),
        tile(Res.string.tile_stock_count, Res.string.tile_stock_count_sub, AppIcons.Inbox, StockCountRoute),
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
        ordersTile(),
        tile(Res.string.tile_record_payment, Res.string.tile_record_payment_sub, AppIcons.Money),
        tile(Res.string.tile_who_owes, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
        tile(Res.string.tile_invoices, Res.string.tile_invoices_register_print, AppIcons.Receipt),
        tile(Res.string.tile_reports, Res.string.tile_reports_export_sub, AppIcons.Chart),
        more(),
    )
    "accounts" -> listOf(
        tile(Res.string.tile_invoices, Res.string.tile_invoices_register_print, AppIcons.Receipt),
        ordersTile(),
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
    val expiringSub: String? = null,
    val approvalsSub: String? = null,
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

class HomeViewModel(private val app: AppState, private val stockRepository: StockRepository) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<HomeEffect> = _effects.asSharedFlow()

    private var expiringLoading = false
    private var approvalsLoading = false

    init {
        viewModelScope.launch {
            app.session.user.collect { user ->
                _state.update {
                    it.copy(
                        name = user?.name ?: "",
                        firstName = user?.name?.trim()?.substringBefore(' ') ?: "",
                        role = user?.role ?: "",
                        tiles = if (user != null) tilesFor(user.role, it.expiringSub, it.approvalsSub) else emptyList(),
                    )
                }
                loadExpiring()
                loadApprovals()
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
            HomeEvent.Resumed -> {
                _state.update { it.copy(greeting = greetingFor(currentHour())) }
                loadExpiring()
                loadApprovals()
            }
        }
    }

    // The Expiring soon square says how many batches need a look. Only people who may see stock ask; errors are ignored.
    private fun loadExpiring() {
        val user = app.user ?: return
        if (!user.canSeeStock() || expiringLoading) return
        expiringLoading = true
        viewModelScope.launch {
            try {
                val sub = expiringSubText(stockRepository.shortDated(days = 90))
                _state.update { it.copy(expiringSub = sub, tiles = tilesFor(it.role, sub, it.approvalsSub)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the plain sub-line until it loads.
            } finally {
                expiringLoading = false
            }
        }
    }

    // The Approvals square says how many write-offs wait for a yes. Only Admin and Management ask; errors are ignored.
    private fun loadApprovals() {
        val user = app.user ?: return
        if (!user.canApproveStock() || approvalsLoading) return
        approvalsLoading = true
        viewModelScope.launch {
            try {
                val sub = approvalsSubText(stockRepository.pendingAdjustments())
                _state.update { it.copy(approvalsSub = sub, tiles = tilesFor(it.role, it.expiringSub, sub)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the plain sub-line until it loads.
            } finally {
                approvalsLoading = false
            }
        }
    }

    private suspend fun approvalsSubText(rows: List<PendingAdjustment>): String = when (rows.size) {
        0 -> getString(Res.string.tile_nothing_waiting)
        1 -> getString(Res.string.tile_approvals_one)
        else -> getString(Res.string.tile_approvals_count, rows.size)
    }

    private suspend fun expiringSubText(batches: List<ShortDatedBatch>): String {
        val expired = batches.count { it.expired }
        val soon = batches.size - expired
        return when {
            batches.isEmpty() -> getString(Res.string.tile_expiring_none)
            expired > 0 -> getString(Res.string.tile_expiring_mixed, expired, soon)
            batches.size == 1 -> getString(Res.string.tile_expiring_one)
            else -> getString(Res.string.tile_expiring_count, batches.size)
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
