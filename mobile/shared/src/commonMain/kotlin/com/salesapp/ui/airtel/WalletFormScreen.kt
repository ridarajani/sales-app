package com.salesapp.ui.airtel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.Company
import com.salesapp.data.UserRow
import com.salesapp.resources.Res
import com.salesapp.resources.airtel_admin_active
import com.salesapp.resources.airtel_admin_companies
import com.salesapp.resources.airtel_admin_edit
import com.salesapp.resources.airtel_admin_holder
import com.salesapp.resources.airtel_admin_holder_help
import com.salesapp.resources.airtel_admin_new
import com.salesapp.resources.airtel_admin_opening
import com.salesapp.resources.airtel_admin_save
import com.salesapp.resources.airtel_admin_saving
import com.salesapp.resources.airtel_no_access
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.ToggleRow
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun WalletFormPage(
    id: Int?,
    onBack: () -> Unit,
    onSaved: () -> Unit,
    viewModel: WalletFormViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                WalletFormEffect.Saved -> onSaved()
            }
        }
    }
    WalletFormScreen(state, viewModel::onEvent, onBack)
}

// Admin says who holds a wallet, what it started with and which companies use it.
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun WalletFormScreen(state: WalletFormState, onEvent: (WalletFormEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            stringResource(if (state.walletId == null) Res.string.airtel_admin_new else Res.string.airtel_admin_edit),
            onBack = onBack,
        )
        when {
            !state.allowed -> Say(stringResource(Res.string.airtel_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.loading -> LoadingState()
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(WalletFormEvent.Retry) })
            else -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    SectionLabel(stringResource(Res.string.airtel_admin_holder))
                    Text(
                        stringResource(Res.string.airtel_admin_holder_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ChoiceColumn {
                        state.holders.forEach { holder ->
                            ChoiceButton(
                                text = holder.name,
                                selected = state.holderId == holder.id,
                                onClick = { onEvent(WalletFormEvent.HolderChosen(holder.id)) },
                                enabled = !state.busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    MwkField(
                        label = stringResource(Res.string.airtel_admin_opening),
                        amount = state.openingBalance,
                        onAmountChange = { onEvent(WalletFormEvent.OpeningChanged(it)) },
                    )
                    SectionLabel(stringResource(Res.string.airtel_admin_companies))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        state.companies.forEach { company ->
                            ChoiceButton(
                                text = company.name,
                                selected = company.code in state.chosen,
                                onClick = { onEvent(WalletFormEvent.CompanyToggled(company.code)) },
                                enabled = !state.busy,
                            )
                        }
                    }
                    ToggleRow(
                        label = stringResource(Res.string.airtel_admin_active),
                        checked = state.active,
                        onCheckedChange = { onEvent(WalletFormEvent.ActiveChanged(it)) },
                    )
                    state.actionError?.let { Say(it, error = true) }
                }
                PrimaryButton(
                    stringResource(if (state.busy) Res.string.airtel_admin_saving else Res.string.airtel_admin_save),
                    onClick = { onEvent(WalletFormEvent.Save) },
                    enabled = !state.busy,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }
    }
}

private val sampleHolders = listOf(
    UserRow(id = 3, name = "Mr. Abbas", phone = "0991000003", role = "sales_manager", companies = listOf("LV", "ZT")),
    UserRow(id = 5, name = "Mr. Kashif", phone = "0991000005", role = "sales_manager", companies = listOf("ZF", "GL")),
)

private val sampleCompanies = listOf(
    Company("LV", "Lucky Ventures", "F1"),
    Company("ZT", "Zoona Trading", "F1"),
    Company("ZF", "Zangafee", "F2"),
    Company("GL", "Greenland", "F2"),
)

@Preview
@Composable
private fun WalletFormNewPreview() {
    SalesTheme {
        WalletFormScreen(
            state = WalletFormState(loading = false, holders = sampleHolders, companies = sampleCompanies),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun WalletFormEditPreview() {
    SalesTheme {
        WalletFormScreen(
            state = WalletFormState(
                loading = false,
                walletId = 1,
                holders = sampleHolders,
                companies = sampleCompanies,
                holderId = 3,
                openingBalance = 500_000,
                chosen = setOf("LV", "ZT"),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
