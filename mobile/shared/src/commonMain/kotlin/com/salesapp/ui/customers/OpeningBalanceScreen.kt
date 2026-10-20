package com.salesapp.ui.customers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.localIsoDateFromUtcTimestamp
import com.salesapp.data.OpeningBalance
import com.salesapp.data.User
import com.salesapp.data.canCorrectOpeningBalance
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.balance_already
import com.salesapp.resources.balance_amount
import com.salesapp.resources.balance_as_of
import com.salesapp.resources.balance_entered
import com.salesapp.resources.balance_explainer
import com.salesapp.resources.balance_title
import com.salesapp.resources.balance_title_correct
import com.salesapp.resources.form_save
import com.salesapp.ui.components.DateField
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun OpeningBalancePage(
    customerId: Int,
    companyCode: String,
    onBack: () -> Unit,
    viewModel: OpeningBalanceViewModel = koinViewModel { parametersOf(customerId, companyCode) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                OpeningBalanceEffect.Done -> onBack()
            }
        }
    }
    OpeningBalanceScreen(state, viewModel::onEvent, onBack)
}

// Type what a customer owed one company before the app started.
@Composable
fun OpeningBalanceScreen(state: OpeningBalanceState, onEvent: (OpeningBalanceEvent) -> Unit, onBack: () -> Unit) {
    val existing = state.existing
    val isAdmin = state.user?.canCorrectOpeningBalance() == true
    val locked = existing != null && !isAdmin
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopBar(
            stringResource(if (existing != null && isAdmin) Res.string.balance_title_correct else Res.string.balance_title),
            onBack = onBack,
        )
        when {
            state.loading -> LoadingState()
            state.loadError != null -> ErrorState(state.loadError, onRetry = { onEvent(OpeningBalanceEvent.Retry) })
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Say(stringResource(Res.string.balance_explainer, state.customerName, companyName(state.companyCode)))
                    MwkField(stringResource(Res.string.balance_amount), state.amount, { onEvent(OpeningBalanceEvent.AmountChanged(it)) })
                    DateField(stringResource(Res.string.balance_as_of), state.asOf, { onEvent(OpeningBalanceEvent.DateChanged(it)) })
                    if (existing != null) {
                        Text(
                            stringResource(Res.string.balance_entered, existing.enteredBy, formatIsoDate(localIsoDateFromUtcTimestamp(existing.enteredAt))),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (locked) Say(stringResource(Res.string.balance_already), error = true)
                    if (state.error != null) Say(state.error, error = true)
                    if (!locked) {
                        PrimaryButton(stringResource(Res.string.form_save), onClick = { onEvent(OpeningBalanceEvent.Save) }, enabled = !state.saving)
                    }
                }
            }
        }
    }
}

private fun previewState(existing: OpeningBalance?, role: String) = OpeningBalanceState(
    loading = false,
    customerName = "Area 47 Superstore",
    companyCode = "LV",
    user = User(1, "Admin", "0990000000", role, listOf("LV", "ZT", "ZF", "GL")),
    existing = existing,
    amount = existing?.amount,
    asOf = existing?.asOf ?: "2026-10-20",
)

@Preview
@Composable
private fun OpeningBalanceNewPreview() {
    SalesTheme { OpeningBalanceScreen(previewState(null, "accounts"), {}, {}) }
}

@Preview
@Composable
private fun OpeningBalanceCorrectPreview() {
    val existing = OpeningBalance(1, "Area 47 Superstore", "LV", 250_000, "2026-10-01", "Grace Banda", "2026-10-02T08:00:00Z")
    SalesTheme { OpeningBalanceScreen(previewState(existing, "admin"), {}, {}) }
}
