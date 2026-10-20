package com.salesapp.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import com.salesapp.data.Screen
import com.salesapp.data.UserRow
import com.salesapp.data.roleLabel
import com.salesapp.ui.components.Avatar
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.Ink
import com.salesapp.ui.theme.Line
import com.salesapp.ui.theme.Muted
import kotlinx.coroutines.CancellationException

// Loads the people list from the server into the shared state.
suspend fun refreshUsers(state: AppState) {
    state.userRows = state.api.users()
}

// The list of people who can sign in, with an Add button for those allowed to add.
@Composable
fun UsersScreen(state: AppState) {
    val me = state.user ?: return
    val isSalesManager = me.role == "sales_manager"
    val canAdd = me.role == "admin" || isSalesManager
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        loading = true
        error = null
        try {
            refreshUsers(state)
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.isSignedOut) state.signedOut("You were signed out. Sign in again.") else error = e.message
        } finally {
            loading = false
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(if (isSalesManager) "My salesmen" else "Users", onBack = { state.nav.pop() })
        Column(modifier = Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            error?.let {
                Say(it, error = true, modifier = Modifier.padding(top = 12.dp))
                SecondaryButton("Try again", onClick = { reload++ })
            }
            if (loading && state.userRows.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = com.salesapp.ui.theme.Burgundy)
                }
            }
            val shown = if (isSalesManager) state.userRows.filter { it.id != me.id } else state.userRows
            if (!loading && error == null && shown.isEmpty()) {
                Text("No one here yet.", color = Muted, fontSize = 15.sp, modifier = Modifier.padding(top = 16.dp))
            }
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                item { Box(modifier = Modifier.padding(top = 2.dp)) }
                items(shown, key = { it.id }) { row ->
                    UserCard(row, onClick = { state.nav.push(Screen.UserDetail(row.id)) })
                }
            }
        }
        if (canAdd) {
            Box(modifier = Modifier.padding(16.dp)) {
                PrimaryButton(
                    if (isSalesManager) "Add a salesman" else "Add a user",
                    onClick = { state.nav.push(Screen.AddUser) },
                )
            }
        }
    }
}

// One person in the list: avatar, name with job badge, and the companies underneath.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserCard(row: UserRow, onClick: () -> Unit) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(Color.White)
            .border(1.dp, Line, shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Avatar(row.name, size = 44.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(roleLabel(row.role))
                if (!row.active) Badge("Off", tone = BadgeTone.Off)
            }
            Text(row.name, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.companies.forEach { CompanyDot(it) }
            }
        }
    }
}
