package com.salesapp.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.resources.Res
import com.salesapp.resources.catalog_save
import com.salesapp.resources.company_address
import com.salesapp.resources.company_email
import com.salesapp.resources.company_name
import com.salesapp.resources.company_phone
import com.salesapp.resources.company_tpin
import com.salesapp.resources.company_tpin_note
import com.salesapp.resources.company_vat
import com.salesapp.resources.company_vat_note
import com.salesapp.resources.companies_title
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MultilineField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CompanyFormPage(
    code: String,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: CompanyFormViewModel = koinViewModel { parametersOf(code) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { onDone() }
    }
    CompanyFormScreen(state, viewModel::onEvent, onBack)
}

// Company details. People who may not edit see the same fields, switched off, and no Save button.
@Composable
fun CompanyFormScreen(state: CompanyFormState, onEvent: (CompanyFormEvent) -> Unit, onBack: () -> Unit) {
    val enabled = !state.readOnly && !state.busy
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopBar(state.title.ifBlank { stringResource(Res.string.companies_title) }, onBack = onBack)
        when {
            state.loading -> LoadingState()
            state.loadError != null -> ErrorState(state.loadError, onRetry = { onEvent(CompanyFormEvent.Retry) })
            else -> {
              Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                Field(stringResource(Res.string.company_name), state.name, { onEvent(CompanyFormEvent.Name(it)) }, enabled = enabled)
                Field(
                    stringResource(Res.string.company_tpin),
                    state.tpin,
                    { onEvent(CompanyFormEvent.Tpin(it)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    note = if (state.readOnly) null else stringResource(Res.string.company_tpin_note),
                    enabled = enabled,
                )
                Field(
                    stringResource(Res.string.company_vat),
                    state.vat,
                    { onEvent(CompanyFormEvent.Vat(it)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    note = if (state.readOnly) null else stringResource(Res.string.company_vat_note),
                    enabled = enabled,
                )
                Field(
                    stringResource(Res.string.company_phone),
                    state.phone,
                    { onEvent(CompanyFormEvent.Phone(it)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                    enabled = enabled,
                )
                Field(
                    stringResource(Res.string.company_email),
                    state.email,
                    { onEvent(CompanyFormEvent.Email(it)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    enabled = enabled,
                )
                MultilineField(stringResource(Res.string.company_address), state.address, { onEvent(CompanyFormEvent.Address(it)) }, enabled = enabled)
              }
              Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                state.error?.let { Say(it, error = true) }
                if (!state.readOnly) {
                    PrimaryButton(
                        stringResource(Res.string.catalog_save),
                        onClick = { onEvent(CompanyFormEvent.Save) },
                        enabled = !state.busy,
                    )
                }
              }
            }
        }
    }
}

@Preview
@Composable
private fun CompanyFormPreview() {
    SalesTheme {
        CompanyFormScreen(
            state = CompanyFormState(
                code = "LV",
                readOnly = false,
                loading = false,
                title = "Lucky Ventures",
                name = "Lucky Ventures",
                tpin = "1234567",
                vat = "16.5",
                phone = "0991234567",
                email = "office@example.com",
                address = "Plot 12, Area 47, Lilongwe",
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
