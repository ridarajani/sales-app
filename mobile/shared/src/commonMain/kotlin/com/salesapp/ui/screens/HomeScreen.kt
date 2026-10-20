package com.salesapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.core.currentHour
import com.salesapp.data.Api
import com.salesapp.data.AppState
import com.salesapp.data.Screen
import com.salesapp.data.User
import com.salesapp.data.roleLabel
import com.salesapp.ui.components.CompanyTag
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.components.Tile
import com.salesapp.ui.theme.AccentSoft
import com.salesapp.ui.theme.Burgundy
import com.salesapp.ui.theme.Muted
import kotlinx.coroutines.launch

// One square on the home screen. If it has no screen yet, `opens` is null.
private class TileDef(val label: String, val sub: String, val opens: Screen? = null)

private val more = TileDef("More", "Everything else")

// The squares each kind of person sees, in order. The first one is highlighted.
private fun tilesFor(role: String): List<TileDef> = when (role) {
    "admin" -> listOf(
        TileDef("Users", "Add, switch off, reset PIN", Screen.Users),
        TileDef("Audit trail", "Who did what"),
        TileDef("Settings", "Companies and rules"),
        TileDef("Reports", "Sales and money owed"),
        more,
    )
    "management" -> listOf(
        TileDef("Today's numbers", "Sales, money owed, cash"),
        TileDef("Approve", "Nothing waiting"),
        TileDef("Loading plans", "Nothing waiting"),
        TileDef("Airtel Money", "Both wallets"),
        TileDef("Reports", "Excel, PDF, print"),
        TileDef("Stock", "Free, reserved, on the road"),
        TileDef("Invoices", "Register"),
        TileDef("Customers", "Who owes money"),
        TileDef("Users", "Who can sign in", Screen.Users),
        more,
    )
    "sales_manager" -> listOf(
        TileDef("New order", "Take an order"),
        TileDef("Loading plan", "Nothing waiting"),
        TileDef("Collect money", "Customer pays you"),
        TileDef("Airtel Money", "Wallet ledger"),
        TileDef("Hand over cash", "Nothing to hand over"),
        TileDef("My salesmen", "Add or switch off", Screen.Users),
        TileDef("My orders", "Balances and status"),
        TileDef("My customers", "Who owes money"),
        TileDef("Invoices", "Print or share"),
        TileDef("Reports", "Sales and money owed"),
        more,
    )
    "salesman" -> listOf(
        TileDef("New order", "Take an order"),
        TileDef("Collect money", "Customer pays you"),
        TileDef("Hand over cash", "Nothing to hand over"),
        TileDef("My orders", "Balances and status"),
        TileDef("My customers", "Who owes money"),
        more,
    )
    "warehouse" -> listOf(
        TileDef("Jobs to do", "Nothing waiting"),
        TileDef("Load a trip van", "Nothing waiting"),
        TileDef("Receive stock", "New cartons arrived"),
        TileDef("Check stock", "Free, reserved, on the road"),
        TileDef("Report damage", "Or a count difference"),
        TileDef("Reports", "Stock and deliveries"),
        more,
    )
    "driver" -> listOf(
        TileDef("Your deliveries", "Nothing waiting"),
        TileDef("Hand over goods", "Signature and photo"),
        TileDef("Report a shortage", "Goods missing or damaged"),
        more,
    )
    "cashier" -> listOf(
        TileDef("Count cash", "Nothing waiting"),
        TileDef("Record a payment", "Bank transfer or cash"),
        TileDef("Who owes money", "Customer balances"),
        TileDef("Invoices", "Register and print"),
        TileDef("Reports", "Excel, PDF, print"),
        more,
    )
    "accounts" -> listOf(
        TileDef("Invoices", "Register and print"),
        TileDef("Credit and debit notes", "Fix an invoice"),
        TileDef("Customer ledger", "Invoices, payments, tax"),
        TileDef("Who owes money", "Customer balances"),
        TileDef("Reports", "Excel, PDF, print"),
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

// The first screen after signing in: a greeting and big tiles for the person's job.
@Composable
fun HomeScreen(state: AppState) {
    val user = state.user ?: return
    var showMenu by remember { mutableStateOf(false) }
    val firstName = user.name.trim().substringBefore(' ')

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            title = "${greeting()}, $firstName",
            sub = { HomeSubLine(user) },
            trailing = {
                Text(
                    user.name,
                    color = Burgundy,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(start = 6.dp)
                        .widthIn(max = 110.dp)
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(AccentSoft)
                        .clickable(role = Role.Button) { showMenu = true }
                        .padding(horizontal = 12.dp, vertical = 14.dp),
                )
            },
        )
        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            tilesFor(user.role).chunked(2).forEachIndexed { rowIndex, pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    pair.forEachIndexed { colIndex, tile ->
                        Tile(
                            label = tile.label,
                            sub = tile.sub,
                            primary = rowIndex == 0 && colIndex == 0,
                            modifier = Modifier.weight(1f),
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
        AlertDialog(
            onDismissRequest = { showMenu = false },
            title = { Text(user.name) },
            text = { Text("Signed in as ${roleLabel(user.role)}.") },
            confirmButton = {
                TextButton(onClick = {
                    showMenu = false
                    // Sign out on the phone at once; tell the server in the background.
                    val token = state.session.token
                    val oldApi = Api(state.apiUrl) { token }
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
                }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Sign out", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { showMenu = false }, modifier = Modifier.heightIn(min = 48.dp)) { Text("Cancel", fontSize = 16.sp) } },
        )
    }
}

// "Sales manager · [dot] Lucky Ventures [dot] Zoona Trading"
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HomeSubLine(user: User) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Text("${roleLabel(user.role)} ·", color = Muted, fontSize = 14.sp)
        user.companies.forEach { CompanyTag(it) }
    }
}
