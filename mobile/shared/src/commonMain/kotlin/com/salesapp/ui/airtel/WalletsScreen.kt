package com.salesapp.ui.airtel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatMwk
import com.salesapp.data.WalletListItem
import com.salesapp.resources.Res
import com.salesapp.resources.airtel_admin_new
import com.salesapp.resources.airtel_holder
import com.salesapp.resources.airtel_no_access
import com.salesapp.resources.airtel_no_wallets
import com.salesapp.resources.airtel_off
import com.salesapp.resources.airtel_title
import com.salesapp.resources.airtel_waiting_many
import com.salesapp.resources.airtel_waiting_none
import com.salesapp.resources.airtel_waiting_one
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel

@Composable
fun WalletsPage(
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    onNew: () -> Unit,
    viewModel: WalletsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(WalletsEvent.Refresh)
        onPauseOrDispose {}
    }
    WalletsScreen(state, viewModel::onEvent, onBack, onOpen, onNew)
}

// Every wallet this user may see: the balance in the app and how many payments wait to be ticked.
@Composable
fun WalletsScreen(
    state: WalletsState,
    onEvent: (WalletsEvent) -> Unit,
    onBack: () -> Unit,
    onOpen: (Int) -> Unit,
    onNew: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(Res.string.airtel_title), onBack = onBack)
        if (!state.allowed) {
            Say(stringResource(Res.string.airtel_no_access), error = true, modifier = Modifier.padding(16.dp))
            return@Column
        }
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                state.loading && state.wallets.isEmpty() -> item { LoadingState() }
                state.error != null -> item { ErrorState(state.error, onRetry = { onEvent(WalletsEvent.Retry) }) }
                state.wallets.isEmpty() -> item { EmptyState(stringResource(Res.string.airtel_no_wallets)) }
                else -> items(state.wallets, key = { it.id }) { wallet ->
                    WalletRow(wallet, onClick = { onOpen(wallet.id) })
                }
            }
        }
        // Only admin sets up who holds which wallet.
        if (state.canSetUp) {
            PrimaryButton(stringResource(Res.string.airtel_admin_new), onClick = onNew, modifier = Modifier.padding(16.dp))
        }
    }
}

@Composable
private fun WalletRow(wallet: WalletListItem, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    ListCard(onClick = onClick) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    stringResource(Res.string.airtel_holder, wallet.holderName),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                if (!wallet.active) Badge(stringResource(Res.string.airtel_off), tone = BadgeTone.Off)
                if (wallet.waiting > 0) {
                    Badge(
                        if (wallet.waiting == 1) {
                            stringResource(Res.string.airtel_waiting_one)
                        } else {
                            stringResource(Res.string.airtel_waiting_many, wallet.waiting)
                        },
                        tone = BadgeTone.Off,
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                wallet.companies.forEach { code -> CompanyDot(code) }
            }
            Text(
                formatMwk(wallet.balance.toLong()),
                color = scheme.onSurface,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (wallet.waiting == 0) {
                Text(stringResource(Res.string.airtel_waiting_none), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.caption)
            }
        }
    }
}

private val sampleWallets = listOf(
    WalletListItem(id = 1, holderName = "Mr. Abbas", holderId = 3, balance = 1_250_000.0, companies = listOf("LV", "ZT"), waiting = 2),
    WalletListItem(id = 2, holderName = "Mr. Kashif", holderId = 5, balance = 480_000.0, companies = listOf("ZF", "GL")),
)

@Preview
@Composable
private fun WalletsPreview() {
    SalesTheme {
        WalletsScreen(state = WalletsState(loading = false, canSetUp = true, wallets = sampleWallets), onEvent = {}, onBack = {}, onOpen = {}, onNew = {})
    }
}

@Preview
@Composable
private fun WalletsEmptyPreview() {
    SalesTheme {
        WalletsScreen(state = WalletsState(loading = false), onEvent = {}, onBack = {}, onOpen = {}, onNew = {})
    }
}
