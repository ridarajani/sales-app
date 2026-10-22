package com.salesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.UserRow
import com.salesapp.data.roleLabel
import com.salesapp.resources.Res
import com.salesapp.resources.badge_off
import com.salesapp.resources.users_add
import com.salesapp.resources.users_add_salesman
import com.salesapp.resources.users_empty
import com.salesapp.resources.users_title
import com.salesapp.resources.users_title_sales_manager
import com.salesapp.ui.components.Avatar
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

// The list of people who can sign in, with an Add button for those allowed to add.
@Composable
fun UsersPage(
    onBack: () -> Unit,
    onOpenUser: (Int) -> Unit,
    onAddUser: () -> Unit,
    viewModel: UsersViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is UsersEffect.OpenUser -> onOpenUser(effect.id)
                UsersEffect.AddUser -> onAddUser()
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(UsersEvent.Refresh)
        onPauseOrDispose {}
    }
    UsersScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun UsersScreen(state: UsersState, onEvent: (UsersEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            stringResource(if (state.isSalesManager) Res.string.users_title_sales_manager else Res.string.users_title),
            onBack = onBack,
        )
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                state.loading && state.users.isEmpty() -> item { LoadingState() }
                state.users.isEmpty() && state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(UsersEvent.Retry) }) }
                state.users.isEmpty() -> item { EmptyState(stringResource(Res.string.users_empty)) }
                else -> {
                    if (state.error != null) {
                        item { ErrorState(state.error, onRetry = { onEvent(UsersEvent.Retry) }) }
                    }
                    items(state.users, key = { it.id }) { row ->
                        UserCard(row, onClick = { onEvent(UsersEvent.OpenUser(row.id)) })
                    }
                }
            }
        }
        if (state.canAdd) {
            PrimaryButton(
                stringResource(if (state.isSalesManager) Res.string.users_add_salesman else Res.string.users_add),
                onClick = { onEvent(UsersEvent.AddUser) },
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

// One person in the list: avatar, name with job badge, and the companies underneath.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UserCard(row: UserRow, onClick: () -> Unit) {
    ListCard(onClick = onClick) {
        Avatar(row.name, size = 44.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Badge(roleLabel(row.role))
                if (!row.active) Badge(stringResource(Res.string.badge_off), tone = BadgeTone.Off)
            }
            Text(
                row.name,
                color = MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                row.companies.forEach { CompanyDot(it) }
            }
        }
    }
}

private val sampleUsers = listOf(
    UserRow(1, "Chisomo Phiri", "0991234567", "salesman", listOf("LV", "ZT"), 7, "Banda Phiri"),
    UserRow(2, "Thoko Banda", "+971501234567", "sales_manager", listOf("LV")),
    UserRow(3, "Mphatso Kamanga", "+265999876543", "driver", listOf("ZF"), active = false),
)

@Preview
@Composable
private fun UsersPreview() {
    SalesTheme {
        UsersScreen(UsersState(loading = false, users = sampleUsers, canAdd = true), onEvent = {}, onBack = {})
    }
}
