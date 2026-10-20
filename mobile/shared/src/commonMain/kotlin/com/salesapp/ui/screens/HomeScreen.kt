package com.salesapp.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.core.currentHour
import com.salesapp.data.Api
import com.salesapp.data.AppState
import com.salesapp.data.ListsStart
import com.salesapp.data.Screen
import com.salesapp.data.roleLabel
import com.salesapp.resources.Res
import com.salesapp.resources.tile_balances_terms
import com.salesapp.resources.tile_companies
import com.salesapp.resources.tile_companies_sub
import com.salesapp.resources.tile_customers
import com.salesapp.resources.tile_customers_add
import com.salesapp.resources.tile_look_up
import com.salesapp.resources.tile_my_customers
import com.salesapp.resources.tile_products
import com.salesapp.resources.tile_products_sub
import com.salesapp.resources.tile_vehicles
import com.salesapp.resources.tile_vehicles_sub
import com.salesapp.resources.tile_who_owes
import com.salesapp.ui.components.AppIcons
import com.salesapp.ui.components.Avatar
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.DangerButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.Tile
import com.salesapp.ui.theme.Ink
import com.salesapp.ui.theme.Muted
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

// One square on the home screen. If it has no screen yet, `opens` is null.
private class TileDef(
    val label: String,
    val sub: String,
    val icon: ImageVector,
    val opens: Screen? = null,
    val labelRes: StringResource? = null,
    val subRes: StringResource? = null,
)

// A tile whose texts come from the string resources.
private fun listTile(label: StringResource, sub: StringResource, icon: ImageVector, opens: Screen) =
    TileDef("", "", icon, opens, label, sub)

private val more = TileDef("More", "Everything else", AppIcons.Grid)

// The squares each kind of person sees, in order. The first one is highlighted.
private fun tilesFor(role: String): List<TileDef> = when (role) {
    "admin" -> listOf(
        TileDef("Users", "Add, switch off, reset PIN", AppIcons.Person, Screen.Users),
        listTile(Res.string.tile_customers, Res.string.tile_customers_add, AppIcons.People, Screen.Lists(ListsStart.Customers)),
        listTile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, Screen.Lists(ListsStart.Products)),
        listTile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, Screen.Lists(ListsStart.Vehicles)),
        listTile(Res.string.tile_companies, Res.string.tile_companies_sub, AppIcons.Settings, Screen.Lists(ListsStart.Companies)),
        TileDef("Audit trail", "Who did what", AppIcons.List),
        TileDef("Reports", "Sales and money owed", AppIcons.Chart),
        more,
    )
    "management" -> listOf(
        TileDef("Today's numbers", "Sales, money owed, cash", AppIcons.Chart),
        TileDef("Approve", "Nothing waiting", AppIcons.Inbox),
        TileDef("Loading plans", "Nothing waiting", AppIcons.Truck),
        TileDef("Airtel Money", "Both wallets", AppIcons.Money),
        TileDef("Reports", "Excel, PDF, print", AppIcons.Chart),
        TileDef("Stock", "Free, reserved, on the road", AppIcons.Box),
        TileDef("Invoices", "Register", AppIcons.Receipt),
        listTile(Res.string.tile_customers, Res.string.tile_look_up, AppIcons.People, Screen.Lists(ListsStart.Customers)),
        listTile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, Screen.Lists(ListsStart.Products)),
        listTile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, Screen.Lists(ListsStart.Vehicles)),
        TileDef("Users", "Who can sign in", AppIcons.Person, Screen.Users),
        more,
    )
    "sales_manager" -> listOf(
        TileDef("New order", "Take an order", AppIcons.Cart),
        TileDef("Loading plan", "Nothing waiting", AppIcons.Truck),
        TileDef("Collect money", "Customer pays you", AppIcons.Money),
        TileDef("Airtel Money", "Wallet ledger", AppIcons.Money),
        TileDef("Hand over cash", "Nothing to hand over", AppIcons.Money),
        TileDef("My salesmen", "Add or switch off", AppIcons.People, Screen.Users),
        TileDef("My orders", "Balances and status", AppIcons.Cart),
        listTile(Res.string.tile_my_customers, Res.string.tile_look_up, AppIcons.People, Screen.Lists(ListsStart.Customers)),
        listTile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, Screen.Lists(ListsStart.Products)),
        TileDef("Invoices", "Print or share", AppIcons.Receipt),
        TileDef("Reports", "Sales and money owed", AppIcons.Chart),
        more,
    )
    "salesman" -> listOf(
        TileDef("New order", "Take an order", AppIcons.Cart),
        TileDef("Collect money", "Customer pays you", AppIcons.Money),
        TileDef("Hand over cash", "Nothing to hand over", AppIcons.Money),
        TileDef("My orders", "Balances and status", AppIcons.Cart),
        listTile(Res.string.tile_my_customers, Res.string.tile_look_up, AppIcons.People, Screen.Lists(ListsStart.Customers)),
        more,
    )
    "warehouse" -> listOf(
        TileDef("Jobs to do", "Nothing waiting", AppIcons.Box),
        TileDef("Load a trip van", "Nothing waiting", AppIcons.Truck),
        TileDef("Receive stock", "New cartons arrived", AppIcons.Box),
        TileDef("Check stock", "Free, reserved, on the road", AppIcons.Box),
        TileDef("Report damage", "Or a count difference", AppIcons.Inbox),
        listTile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, Screen.Lists(ListsStart.Products)),
        listTile(Res.string.tile_vehicles, Res.string.tile_vehicles_sub, AppIcons.Truck, Screen.Lists(ListsStart.Vehicles)),
        TileDef("Reports", "Stock and deliveries", AppIcons.Chart),
        more,
    )
    "driver" -> listOf(
        TileDef("Your deliveries", "Nothing waiting", AppIcons.Truck),
        TileDef("Hand over goods", "Signature and photo", AppIcons.Box),
        TileDef("Report a shortage", "Goods missing or damaged", AppIcons.Inbox),
        more,
    )
    "cashier" -> listOf(
        TileDef("Count cash", "Nothing waiting", AppIcons.Money),
        TileDef("Record a payment", "Bank transfer or cash", AppIcons.Money),
        listTile(Res.string.tile_who_owes, Res.string.tile_look_up, AppIcons.People, Screen.Lists(ListsStart.Customers)),
        TileDef("Invoices", "Register and print", AppIcons.Receipt),
        TileDef("Reports", "Excel, PDF, print", AppIcons.Chart),
        more,
    )
    "accounts" -> listOf(
        TileDef("Invoices", "Register and print", AppIcons.Receipt),
        TileDef("Credit and debit notes", "Fix an invoice", AppIcons.Receipt),
        TileDef("Customer ledger", "Invoices, payments, tax", AppIcons.List),
        listTile(Res.string.tile_customers, Res.string.tile_balances_terms, AppIcons.People, Screen.Lists(ListsStart.Customers)),
        listTile(Res.string.tile_products, Res.string.tile_products_sub, AppIcons.Box, Screen.Lists(ListsStart.Products)),
        TileDef("Reports", "Excel, PDF, print", AppIcons.Chart),
        more,
    )
    else -> listOf(more)
}

private fun greeting(): String {
    val hour = currentHour()
    return when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

// The first screen after signing in: a greeting with the person's picture, then cards for the person's job.
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: AppState) {
    val user = state.user ?: return
    var showMenu by remember { mutableStateOf(false) }
    val firstName = user.name.trim().substringBefore(' ')

    Column(modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${greeting()},", color = Muted, fontSize = 14.sp)
                Text(firstName, color = Ink, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Badge(roleLabel(user.role))
            }
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = "Your account" }
                    .clickable(role = Role.Button) { showMenu = true },
                contentAlignment = Alignment.Center,
            ) {
                Avatar(user.name, size = 44.dp)
            }
        }
        SectionLabel("What do you want to do?", modifier = Modifier.padding(top = 24.dp, bottom = 12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            tilesFor(user.role).chunked(2).forEachIndexed { rowIndex, pair ->
                // Both tiles in a row are as tall as the taller one.
                Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Max), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEachIndexed { colIndex, tile ->
                        Tile(
                            label = tile.labelRes?.let { stringResource(it) } ?: tile.label,
                            sub = tile.subRes?.let { stringResource(it) } ?: tile.sub,
                            icon = tile.icon,
                            primary = rowIndex == 0 && colIndex == 0,
                            modifier = Modifier.weight(1f).fillMaxHeight(),
                            onClick = {
                                val target = tile.opens
                                if (target != null) state.nav.push(target) else state.message = "This is coming soon."
                            },
                        )
                    }
                    if (pair.size == 1) Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }

    if (showMenu) {
        ModalBottomSheet(onDismissRequest = { showMenu = false }, containerColor = Color.White) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(user.name, color = Ink, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Badge(roleLabel(user.role))
                DangerButton("Sign out", onClick = {
                    showMenu = false
                    // Sign out on the phone at once; tell the server in the background.
                    val token = state.session.token
                    val oldApi = Api({ state.apiUrl }) { token }
                    state.signedOut()
                    state.backgroundScope.launch {
                        try {
                            oldApi.logout()
                        } catch (e: Exception) {
                            // Signing out on the phone works even if the server cannot be reached.
                        } finally {
                            oldApi.close()
                        }
                    }
                })
            }
        }
    }
}
