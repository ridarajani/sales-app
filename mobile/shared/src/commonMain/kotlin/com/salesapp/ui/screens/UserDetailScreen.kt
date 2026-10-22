package com.salesapp.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontWeight
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatPhone
import com.salesapp.data.UserManager
import com.salesapp.data.UserRow
import com.salesapp.data.companyName
import com.salesapp.data.roleLabel
import com.salesapp.resources.Res
import com.salesapp.resources.badge_switched_off
import com.salesapp.resources.user_detail_can_sign_in
import com.salesapp.resources.user_detail_confirm_text
import com.salesapp.resources.user_detail_confirm_title
import com.salesapp.resources.user_detail_not_found
import com.salesapp.resources.user_detail_reset_pin
import com.salesapp.resources.user_detail_switch_off
import com.salesapp.resources.user_detail_switch_on
import com.salesapp.resources.user_detail_this_is_you
import com.salesapp.resources.user_detail_title_fallback
import com.salesapp.resources.users_label_companies
import com.salesapp.resources.users_label_phone
import com.salesapp.resources.users_label_works_under
import com.salesapp.ui.components.Avatar
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.ConfirmDialog
import com.salesapp.ui.components.DangerButton
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

// Everything about one person, with buttons to reset their PIN or switch them off or on.
@Composable
fun UserDetailPage(
    id: Int,
    onBack: () -> Unit,
    onResetPin: (Int) -> Unit,
    viewModel: UserDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is UserDetailEffect.ResetPin -> onResetPin(effect.id)
            }
        }
    }
    UserDetailScreen(state, viewModel::onEvent, onBack)
}

@Composable
fun UserDetailScreen(state: UserDetailState, onEvent: (UserDetailEvent) -> Unit, onBack: () -> Unit) {
    val row = state.user
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(row?.name ?: stringResource(Res.string.user_detail_title_fallback), onBack = onBack)
        when {
            row == null && state.loading -> LoadingState()
            row == null && state.error != null -> ErrorState(state.error, onRetry = { onEvent(UserDetailEvent.Retry) })
            row == null -> Say(stringResource(Res.string.user_detail_not_found), modifier = Modifier.padding(16.dp))
            else -> UserDetailBody(state, row, onEvent)
        }
    }
}

@Composable
private fun UserDetailBody(state: UserDetailState, row: UserRow, onEvent: (UserDetailEvent) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        state.error?.let { Say(it, error = true) }
        ListCard(onClick = null) {
            Avatar(row.name, size = 56.dp)
            Column(modifier = Modifier.padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Badge(roleLabel(row.role))
                    Badge(
                        stringResource(if (row.active) Res.string.user_detail_can_sign_in else Res.string.badge_switched_off),
                        tone = if (row.active) BadgeTone.On else BadgeTone.Off,
                    )
                }
                Text(row.name, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
            }
        }
        Detail(stringResource(Res.string.users_label_phone), formatPhone(row.phone))
        Detail(stringResource(Res.string.users_label_companies), row.companies.joinToString(", ") { companyName(it) })
        if (row.managers.isNotEmpty()) {
            Detail(
                stringResource(Res.string.users_label_works_under),
                row.managers.joinToString("\n") { "${companyName(it.company)}: ${it.managerName}" },
            )
        } else if (row.managerName != null) Detail(stringResource(Res.string.users_label_works_under), row.managerName)
        if (state.isMe) {
            Say(stringResource(Res.string.user_detail_this_is_you))
        } else if (state.canManage) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    stringResource(Res.string.user_detail_reset_pin),
                    modifier = Modifier.weight(1f).height(44.dp),
                    enabled = !state.busy,
                    onClick = { onEvent(UserDetailEvent.ResetPin) },
                )
                if (row.active) {
                    DangerButton(
                        stringResource(Res.string.user_detail_switch_off),
                        modifier = Modifier.weight(1f),
                        enabled = !state.busy,
                        onClick = { onEvent(UserDetailEvent.AskSwitchOff) },
                    )
                } else {
                    SecondaryButton(
                        stringResource(Res.string.user_detail_switch_on),
                        modifier = Modifier.weight(1f),
                        enabled = !state.busy,
                        onClick = { onEvent(UserDetailEvent.SwitchOn) },
                    )
                }
            }
        }
    }
    if (state.confirmOff) {
        ConfirmDialog(
            title = stringResource(Res.string.user_detail_confirm_title, row.name),
            text = stringResource(Res.string.user_detail_confirm_text),
            confirmLabel = stringResource(Res.string.user_detail_switch_off),
            onConfirm = { onEvent(UserDetailEvent.ConfirmSwitchOff) },
            onDismiss = { onEvent(UserDetailEvent.DismissSwitchOff) },
        )
    }
}

// A small grey label with the value under it.
@Composable
private fun Detail(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal))
        Text(value, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

private val sampleUser = UserRow(1, "Chisomo Phiri", "0991234567", "salesman", listOf("LV", "ZT"), 7, "Banda Phiri",
    managers = listOf(UserManager("LV", 7, "Banda Phiri"), UserManager("ZT", 8, "Mr Abbas")),
)

@Preview
@Composable
private fun UserDetailPreview() {
    SalesTheme {
        UserDetailScreen(
            UserDetailState(loading = false, user = sampleUser, myId = 99, myRole = "admin"),
            onEvent = {},
            onBack = {},
        )
    }
}
