package com.salesapp.ui.screens

import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.currentHour
import com.salesapp.data.Api
import com.salesapp.core.formatMwk
import com.salesapp.data.AirtelRepository
import com.salesapp.data.AppState
import com.salesapp.data.CashRepository
import com.salesapp.data.ReportsRepository
import com.salesapp.data.ReturnsRepository
import com.salesapp.data.TripsRepository
import com.salesapp.data.LoadingRepository
import com.salesapp.data.PendingAdjustment
import com.salesapp.data.ShortDatedBatch
import com.salesapp.data.StockRepository
import com.salesapp.data.canApproveStock
import com.salesapp.data.canCountCash
import com.salesapp.data.canReceiveReturns
import com.salesapp.data.canSeePlans
import com.salesapp.data.canSeeTrips
import com.salesapp.data.canSeeWallets
import com.salesapp.data.holdsCash
import com.salesapp.data.canSeeStock
import com.salesapp.data.canSetTargets
import com.salesapp.data.seesPlanChanges
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
import com.salesapp.resources.airtel_tile_ledger
import com.salesapp.resources.airtel_tile_many
import com.salesapp.resources.airtel_tile_one
import com.salesapp.resources.cash_tile_amount
import com.salesapp.resources.cash_tile_nothing
import com.salesapp.resources.count_tile_many
import com.salesapp.resources.count_tile_nothing
import com.salesapp.resources.count_tile_one
import com.salesapp.resources.tile_expenses
import com.salesapp.resources.tile_expenses_sub
import com.salesapp.resources.tile_expiring_one
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
import com.salesapp.resources.tile_loading
import com.salesapp.resources.tile_loading_sub
import com.salesapp.resources.tile_look_up
import com.salesapp.resources.tile_more
import com.salesapp.resources.tile_my_trips
import com.salesapp.resources.tile_payments
import com.salesapp.resources.tile_payments_sub
import com.salesapp.resources.tile_my_trips_sub
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
import com.salesapp.resources.tile_ledger_card
import com.salesapp.resources.tile_ledger_card_sub
import com.salesapp.resources.tile_reports
import com.salesapp.resources.tile_returns
import com.salesapp.resources.tile_returns_receive_many
import com.salesapp.resources.tile_returns_receive_one
import com.salesapp.resources.tile_returns_sub
import com.salesapp.resources.tile_trips
import com.salesapp.resources.tile_trips_many
import com.salesapp.resources.tile_trips_none
import com.salesapp.resources.tile_trips_one
import com.salesapp.resources.tile_trips_sub
import com.salesapp.resources.tile_reports_mine_sub
import com.salesapp.resources.tile_reports_stock_sub
import com.salesapp.resources.tile_reports_sub
import com.salesapp.resources.tile_targets
import com.salesapp.resources.tile_targets_sub
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
import com.salesapp.ui.navigation.CollectMoneyRoute
import com.salesapp.ui.navigation.CountCashRoute
import com.salesapp.ui.navigation.ExpensesRoute
import com.salesapp.ui.navigation.InvoicesRoute
import com.salesapp.ui.navigation.LedgerRoute
import com.salesapp.ui.navigation.ReturnsRoute
import com.salesapp.ui.navigation.TripsRoute
import com.salesapp.ui.navigation.MyCashRoute
import com.salesapp.ui.navigation.PaymentsRoute
import com.salesapp.ui.navigation.WalletsRoute
import com.salesapp.ui.navigation.NewOrderRoute
import com.salesapp.ui.navigation.OrdersRoute
import com.salesapp.ui.navigation.PlansRoute
import com.salesapp.ui.navigation.ProductsRoute
import com.salesapp.ui.navigation.ReceiveStockRoute
import com.salesapp.ui.navigation.StockCountRoute
import com.salesapp.ui.navigation.ReportsRoute
import com.salesapp.ui.navigation.StockRoute
import com.salesapp.ui.navigation.TargetsRoute
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
    // A small red number on the tile when above 0.
    val count: Int? = null,
)

private fun tile(label: StringResource, sub: StringResource, icon: ImageVector, opens: Any? = null, subText: String? = null, count: Int? = null) =
    TileDef(label, sub, icon, opens, subText, count)

private fun expiringTile(subText: String?) =
    tile(Res.string.tile_expiring, Res.string.tile_expiring_loading, AppIcons.Inbox, ExpiringRoute, subText)

private fun approvalsTile(subText: String?) =
    tile(Res.string.tile_approvals, Res.string.tile_approvals_loading, AppIcons.Inbox, ApprovalsRoute, subText)

private fun more() = tile(Res.string.tile_more, Res.string.tile_more_sub, AppIcons.Grid)

// Phase 10. The server decides which reports a person may open, so the square is left out
// altogether for anyone it gives none to (a driver). The sub-line says what that person's reports
// are about: the warehouse has only stock, a salesman only his own figures.
private fun reportsTile(sub: StringResource = Res.string.tile_reports_sub) =
    tile(Res.string.tile_reports, sub, AppIcons.Chart, ReportsRoute)

private fun targetsTile() = tile(Res.string.tile_targets, Res.string.tile_targets_sub, AppIcons.Chart, TargetsRoute)

private fun newOrderTile() = tile(Res.string.tile_new_order, Res.string.tile_new_order_sub, AppIcons.Cart, NewOrderRoute)

private fun ordersTile() = tile(Res.string.tile_orders, Res.string.tile_orders_sub, AppIcons.Receipt, OrdersRoute)

// The invoice register; the sub-line differs by role (share, register, print).
private fun invoicesTile(sub: StringResource) = tile(Res.string.tile_invoices, sub, AppIcons.Receipt, InvoicesRoute)

// Phase 7. Collecting money is called "Record a payment" at the office.
private fun collectTile(sub: StringResource) = tile(Res.string.tile_collect_money, sub, AppIcons.Money, CollectMoneyRoute())

private fun recordPaymentTile() = tile(Res.string.tile_record_payment, Res.string.tile_record_payment_sub, AppIcons.Money, CollectMoneyRoute())

// The sub-line says how much is waiting to be handed over.
private fun handOverTile(subText: String?) =
    tile(Res.string.tile_hand_over_cash, Res.string.tile_hand_over_cash_sub, AppIcons.Money, MyCashRoute, subText)

private fun countCashTile(subText: String?, count: Int?) =
    tile(Res.string.tile_count_cash, Res.string.tile_nothing_waiting, AppIcons.Money, CountCashRoute, subText, count)

private fun airtelTile(sub: StringResource, subText: String?, count: Int?) =
    tile(Res.string.tile_airtel, sub, AppIcons.Money, WalletsRoute, subText, count)

private fun expensesTile() = tile(Res.string.tile_expenses, Res.string.tile_expenses_sub, AppIcons.Receipt, ExpensesRoute)

private fun paymentsTile() = tile(Res.string.tile_payments, Res.string.tile_payments_sub, AppIcons.Receipt, PaymentsRoute)

// Phase 8. The warehouse's sub-line says how many are waiting for the goods.
private fun returnsTile(subText: String? = null, count: Int? = null) =
    tile(Res.string.tile_returns, Res.string.tile_returns_sub, AppIcons.Inbox, ReturnsRoute, subText, count)

private fun ledgerTile() = tile(Res.string.tile_ledger_card, Res.string.tile_ledger_card_sub, AppIcons.List, LedgerRoute())

// Phase 9: minibus routes and upcountry trips, with how many are on the road.
private fun tripsTile(subText: String?, count: Int?) =
    tile(Res.string.tile_trips, Res.string.tile_trips_sub, AppIcons.Truck, TripsRoute, subText, count)

// Loading plans; count is how many changes from the other side wait to be seen.
private fun loadingTile(count: Int?) = tile(Res.string.tile_loading, Res.string.tile_loading_sub, AppIcons.Truck, PlansRoute, count = count)

private fun myTripsTile() = tile(Res.string.tile_my_trips, Res.string.tile_my_trips_sub, AppIcons.Truck, PlansRoute)

// The squares each kind of person sees, in order. The first one is highlighted.
// Only admin, management and warehouse get stock squares (the same roles as canSeeStock); no other role lists them.
// Everyone but drivers gets Orders (canSeeOrders); those who book (canBookOrders) start with New order.
// Everyone who may see loading plans (canSeePlans) gets Loading, or My trips for a driver; never a salesman.
internal fun tilesFor(
    role: String,
    hasReports: Boolean = false,
    canSetTargets: Boolean = false,
    expiringSub: String? = null,
    approvalsSub: String? = null,
    loadingCount: Int? = null,
    cashSub: String? = null,
    countSub: String? = null,
    countCount: Int? = null,
    airtelSub: String? = null,
    airtelCount: Int? = null,
    returnsSub: String? = null,
    returnsCount: Int? = null,
    tripsSub: String? = null,
    tripsCount: Int? = null,
): List<TileDef> {
    val tiles = when (role) {
        "admin" -> listOf(
            newOrderTile(),
            tile(Res.string.tile_users, Res.string.tile_users_admin_sub, AppIcons.Person, UsersRoute),
            tile(Res.string.tile_customers, Res.string.tile_customers_add, AppIcons.People, CustomersRoute),
            ordersTile(),
            loadingTile(loadingCount),
            tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
            tile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, VehiclesRoute),
            tile(Res.string.tile_companies, Res.string.tile_companies_sub, AppIcons.Settings, CompaniesRoute),
            tile(Res.string.tile_stock, Res.string.tile_stock_warehouse_sub, AppIcons.Box, StockRoute),
            expiringTile(expiringSub),
            approvalsTile(approvalsSub),
            invoicesTile(Res.string.tile_invoices_register_print),
            recordPaymentTile(),
            paymentsTile(),
            returnsTile(returnsSub, returnsCount),
            ledgerTile(),
            tripsTile(tripsSub, tripsCount),
            expensesTile(),
            handOverTile(cashSub),
            countCashTile(countSub, countCount),
            airtelTile(Res.string.tile_airtel_both, airtelSub, airtelCount),
            tile(Res.string.tile_audit, Res.string.tile_audit_sub, AppIcons.List),
            reportsTile(),
            targetsTile(),
            more(),
        )
        "management" -> listOf(
            tile(Res.string.tile_today, Res.string.tile_today_sub, AppIcons.Chart),
            ordersTile(),
            approvalsTile(approvalsSub),
            loadingTile(loadingCount),
            airtelTile(Res.string.tile_airtel_both, airtelSub, airtelCount),
            paymentsTile(),
            returnsTile(),
            ledgerTile(),
            tripsTile(tripsSub, tripsCount),
            reportsTile(),
            targetsTile(),
            tile(Res.string.tile_stock, Res.string.tile_stock_sub, AppIcons.Box, StockRoute),
            expiringTile(expiringSub),
            invoicesTile(Res.string.tile_invoices_register),
            tile(Res.string.tile_customers, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
            tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
            tile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, VehiclesRoute),
            tile(Res.string.tile_users, Res.string.tile_users_manage_sub, AppIcons.Person, UsersRoute),
            more(),
        )
        "sales_manager" -> listOf(
            newOrderTile(),
            loadingTile(loadingCount),
            collectTile(Res.string.tile_collect_money_sub),
            airtelTile(Res.string.tile_airtel_ledger, airtelSub, airtelCount),
            handOverTile(cashSub),
            expensesTile(),
            returnsTile(),
            ledgerTile(),
            tripsTile(tripsSub, tripsCount),
            tile(Res.string.tile_my_salesmen, Res.string.tile_my_salesmen_sub, AppIcons.People, UsersRoute),
            ordersTile(),
            tile(Res.string.tile_my_customers, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
            tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
            tile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, VehiclesRoute),
            invoicesTile(Res.string.tile_invoices_share),
            reportsTile(),
            targetsTile(),
            more(),
        )
        "salesman" -> listOf(
            newOrderTile(),
            collectTile(Res.string.tile_collect_money_sub),
            handOverTile(cashSub),
            expensesTile(),
            ordersTile(),
            tile(Res.string.tile_my_customers, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
            invoicesTile(Res.string.tile_invoices_share),
            returnsTile(),
            ledgerTile(),
            tripsTile(tripsSub, tripsCount),
            reportsTile(Res.string.tile_reports_mine_sub),
            more(),
        )
        "warehouse" -> listOf(
            tile(Res.string.tile_jobs, Res.string.tile_nothing_waiting, AppIcons.Box),
            ordersTile(),
            loadingTile(loadingCount),
            tile(Res.string.tile_receive_stock, Res.string.tile_receive_stock_sub, AppIcons.Box, ReceiveStockRoute),
            tile(Res.string.tile_stock, Res.string.tile_stock_warehouse_sub, AppIcons.Box, StockRoute),
            expiringTile(expiringSub),
            tile(Res.string.tile_stock_count, Res.string.tile_stock_count_sub, AppIcons.Inbox, StockCountRoute),
            returnsTile(returnsSub, returnsCount),
            tripsTile(tripsSub, tripsCount),
            tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
            tile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, VehiclesRoute),
            reportsTile(Res.string.tile_reports_stock_sub),
            more(),
        )
        "driver" -> listOf(
            myTripsTile(),
            tile(Res.string.tile_hand_over_goods, Res.string.tile_hand_over_goods_sub, AppIcons.Box),
            tile(Res.string.tile_report_shortage, Res.string.tile_report_shortage_sub, AppIcons.Inbox),
            invoicesTile(Res.string.tile_invoices_register),
            tripsTile(tripsSub, tripsCount),
            more(),
        )
        "cashier" -> listOf(
            countCashTile(countSub, countCount),
            ordersTile(),
            loadingTile(loadingCount),
            recordPaymentTile(),
            paymentsTile(),
            ledgerTile(),
            tripsTile(tripsSub, tripsCount),
            airtelTile(Res.string.tile_airtel_ledger, airtelSub, airtelCount),
            tile(Res.string.tile_who_owes, Res.string.tile_look_up, AppIcons.People, CustomersRoute),
            invoicesTile(Res.string.tile_invoices_register_print),
            reportsTile(),
            more(),
        )
        "accounts" -> listOf(
            invoicesTile(Res.string.tile_invoices_register_print),
            recordPaymentTile(),
            paymentsTile(),
            handOverTile(cashSub),
            returnsTile(returnsSub, returnsCount),
            ledgerTile(),
            airtelTile(Res.string.tile_airtel_ledger, airtelSub, airtelCount),
            tripsTile(tripsSub, tripsCount),
            ordersTile(),
            loadingTile(loadingCount),
            tile(Res.string.tile_credit_notes, Res.string.tile_credit_notes_sub, AppIcons.Receipt),
            tile(Res.string.tile_ledger, Res.string.tile_ledger_sub, AppIcons.List),
            tile(Res.string.tile_customers, Res.string.tile_balances_terms, AppIcons.People, CustomersRoute),
            tile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, ProductsRoute),
            reportsTile(),
            more(),
        )
        else -> listOf(more())
    }
    // The Reports square waits until the server has said this person has reports to open, and the
    // Targets square is only for those who may set them.
    return tiles.filterNot {
        (it.labelRes == Res.string.tile_reports && !hasReports) ||
            (it.labelRes == Res.string.tile_targets && !canSetTargets)
    }
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
    val loadingCount: Int? = null,
    // Phase 7: what this person holds, what waits to be counted, and what waits to be ticked.
    val cashSub: String? = null,
    val countSub: String? = null,
    val countCount: Int? = null,
    val airtelSub: String? = null,
    val airtelCount: Int? = null,
    // Phase 8: returns waiting for the warehouse to take the goods back.
    val returnsSub: String? = null,
    val returnsCount: Int? = null,
    // Phase 9: how many trips are on the road.
    val tripsSub: String? = null,
    val tripsCount: Int? = null,
    // Phase 10: true once the server has said this person has reports to open.
    val hasReports: Boolean = false,
    val canSetTargets: Boolean = false,
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

class HomeViewModel(
    private val app: AppState,
    private val stockRepository: StockRepository,
    private val loadingRepository: LoadingRepository,
    private val cashRepository: CashRepository,
    private val airtelRepository: AirtelRepository,
    private val returnsRepository: ReturnsRepository,
    private val tripsRepository: TripsRepository,
    private val reportsRepository: ReportsRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(initialState())
    val state: StateFlow<HomeState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<HomeEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<HomeEffect> = _effects.asSharedFlow()

    private var expiringLoading = false
    private var approvalsLoading = false
    private var loadingLoading = false
    private var moneyLoading = false
    private var reportsLoading = false

    // Every tile list is built from the state, so a new sub-line is never forgotten.
    private fun tilesOf(s: HomeState, role: String = s.role): List<TileDef> =
        tilesFor(
            role, s.hasReports, s.canSetTargets, s.expiringSub, s.approvalsSub, s.loadingCount, s.cashSub,
            s.countSub, s.countCount, s.airtelSub, s.airtelCount, s.returnsSub, s.returnsCount,
            s.tripsSub, s.tripsCount,
        )

    init {
        viewModelScope.launch {
            app.session.user.collect { user ->
                _state.update {
                    it.copy(
                        name = user?.name ?: "",
                        firstName = user?.name?.trim()?.substringBefore(' ') ?: "",
                        role = user?.role ?: "",
                        tiles = if (user != null) tilesOf(it, user.role) else emptyList(),
                    )
                }
                loadExpiring()
                loadApprovals()
                loadLoading()
                loadMoney()
                loadReports()
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
                loadLoading()
                loadMoney()
                loadReports()
            }
        }
    }

    // Which reports a person may open is the server's business, so the Reports square waits for its
    // answer; a driver is given none and never sees the square. Errors are ignored.
    private fun loadReports() {
        val user = app.user ?: return
        _state.update { val next = it.copy(canSetTargets = user.canSetTargets()); next.copy(tiles = tilesOf(next)) }
        if (reportsLoading || _state.value.hasReports) return
        reportsLoading = true
        viewModelScope.launch {
            try {
                val has = reportsRepository.catalogue().groups.isNotEmpty()
                _state.update { val next = it.copy(hasReports = has); next.copy(tiles = tilesOf(next)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // No square until the server answers.
            } finally {
                reportsLoading = false
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
                _state.update { val next = it.copy(expiringSub = sub); next.copy(tiles = tilesOf(next)) }
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
                _state.update { val next = it.copy(approvalsSub = sub); next.copy(tiles = tilesOf(next)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the plain sub-line until it loads.
            } finally {
                approvalsLoading = false
            }
        }
    }

    // The Loading square shows how many changes from the other side wait to be seen.
    // Only people who may see plans ask, and only planners and the warehouse get a number; errors are ignored.
    private fun loadLoading() {
        val user = app.user ?: return
        if (!user.canSeePlans() || loadingLoading) return
        loadingLoading = true
        viewModelScope.launch {
            try {
                val count = if (user.seesPlanChanges()) loadingRepository.newChangesTotal() else null
                _state.update { val next = it.copy(loadingCount = count); next.copy(tiles = tilesOf(next)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the tile without a number until it loads.
            } finally {
                loadingLoading = false
            }
        }
    }

    // Phase 7 sub-lines. Each one only runs for the roles that have that tile.
    private fun loadMoney() {
        val user = app.user ?: return
        if (moneyLoading) return
        moneyLoading = true
        viewModelScope.launch {
            try {
                if (user.holdsCash()) {
                    val net = cashRepository.myCash().net.toLong()
                    val sub = if (net > 0) {
                        getString(Res.string.cash_tile_amount, formatMwk(net))
                    } else {
                        getString(Res.string.cash_tile_nothing)
                    }
                    _state.update { val next = it.copy(cashSub = sub); next.copy(tiles = tilesOf(next)) }
                }
                if (user.canCountCash()) {
                    val waiting = cashRepository.handovers("handed").size
                    val sub = when (waiting) {
                        0 -> getString(Res.string.count_tile_nothing)
                        1 -> getString(Res.string.count_tile_one)
                        else -> getString(Res.string.count_tile_many, waiting)
                    }
                    _state.update { val next = it.copy(countSub = sub, countCount = waiting); next.copy(tiles = tilesOf(next)) }
                }
                if (user.canReceiveReturns()) {
                    val waiting = returnsRepository.waitingTotal()
                    val sub = when (waiting) {
                        0 -> getString(Res.string.tile_nothing_waiting)
                        1 -> getString(Res.string.tile_returns_receive_one)
                        else -> getString(Res.string.tile_returns_receive_many, waiting)
                    }
                    _state.update { val next = it.copy(returnsSub = sub, returnsCount = waiting); next.copy(tiles = tilesOf(next)) }
                }
                if (user.canSeeTrips()) {
                    val onRoad = tripsRepository.trips("out", null).size
                    val sub = when (onRoad) {
                        0 -> getString(Res.string.tile_trips_none)
                        1 -> getString(Res.string.tile_trips_one)
                        else -> getString(Res.string.tile_trips_many, onRoad)
                    }
                    _state.update { val next = it.copy(tripsSub = sub, tripsCount = onRoad); next.copy(tiles = tilesOf(next)) }
                }
                if (user.canSeeWallets()) {
                    val waiting = airtelRepository.waitingTotal()
                    val sub = when (waiting) {
                        0 -> getString(Res.string.airtel_tile_ledger)
                        1 -> getString(Res.string.airtel_tile_one)
                        else -> getString(Res.string.airtel_tile_many, waiting)
                    }
                    _state.update { val next = it.copy(airtelSub = sub, airtelCount = waiting); next.copy(tiles = tilesOf(next)) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep the tiles without their numbers until they load.
            } finally {
                moneyLoading = false
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
