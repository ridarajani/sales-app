package com.salesapp.ui.customers

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
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
import com.salesapp.core.channelLabel
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.form_add_title
import com.salesapp.resources.form_address
import com.salesapp.resources.form_address_hint
import com.salesapp.resources.form_city
import com.salesapp.resources.form_city_hint
import com.salesapp.resources.form_reg_no
import com.salesapp.resources.form_tpin
import com.salesapp.resources.form_area
import com.salesapp.resources.form_area_hint
import com.salesapp.resources.form_can_buy
import com.salesapp.resources.form_can_buy_note
import com.salesapp.resources.form_cash_only
import com.salesapp.resources.form_cash_only_note
import com.salesapp.resources.form_companies
import com.salesapp.resources.form_days
import com.salesapp.resources.form_edit_title
import com.salesapp.resources.form_name
import com.salesapp.resources.form_needs_lpo
import com.salesapp.resources.form_needs_lpo_note
import com.salesapp.resources.form_phone
import com.salesapp.resources.form_save
import com.salesapp.resources.form_type
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.DeleteButton
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
fun CustomerFormPage(
    id: Int?,
    onBack: () -> Unit,
    viewModel: CustomerFormViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                CustomerFormEffect.Done -> onBack()
            }
        }
    }
    CustomerFormScreen(state, viewModel::onEvent, onBack)
}

// Add a customer, or change one.
@Composable
fun CustomerFormScreen(state: CustomerFormState, onEvent: (CustomerFormEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopBar(
            stringResource(if (state.isEdit) Res.string.form_edit_title else Res.string.form_add_title),
            onBack = onBack,
        )
        when {
            state.loading -> LoadingState()
            state.loadError != null -> ErrorState(state.loadError, onRetry = { onEvent(CustomerFormEvent.Retry) })
            else -> FormBody(state, onEvent)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.FormBody(state: CustomerFormState, onEvent: (CustomerFormEvent) -> Unit) {
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Field(stringResource(Res.string.form_name), state.name, { onEvent(CustomerFormEvent.NameChanged(it)) })
        Field(
            stringResource(Res.string.form_area),
            state.area,
            { onEvent(CustomerFormEvent.AreaChanged(it)) },
            placeholder = stringResource(Res.string.form_area_hint),
        )
        Field(
            stringResource(Res.string.form_phone),
            state.phone,
            { onEvent(CustomerFormEvent.PhoneChanged(it)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
        )
        Field(
            stringResource(Res.string.form_address),
            state.address,
            { onEvent(CustomerFormEvent.AddressChanged(it)) },
            placeholder = stringResource(Res.string.form_address_hint),
        )
        Field(
            stringResource(Res.string.form_city),
            state.city,
            { onEvent(CustomerFormEvent.CityChanged(it)) },
            placeholder = stringResource(Res.string.form_city_hint),
        )
        Field(
            stringResource(Res.string.form_tpin),
            state.tpin,
            { onEvent(CustomerFormEvent.TpinChanged(it)) },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
        Field(stringResource(Res.string.form_reg_no), state.regNo, { onEvent(CustomerFormEvent.RegNoChanged(it)) })
        SectionLabel(stringResource(Res.string.form_type))
        ChoiceColumn {
            CustomerChannels.forEach { code ->
                ChoiceButton(channelLabel(code), state.channel == code, { onEvent(CustomerFormEvent.ChannelPicked(code)) }, Modifier.fillMaxWidth())
            }
        }
        SectionLabel(stringResource(Res.string.form_companies))
        ChoiceColumn {
            state.availableCompanies.forEach { code ->
                val picked = code in state.selected
                ChoiceButton(companyName(code), picked, { onEvent(CustomerFormEvent.CompanyToggled(code)) }, Modifier.fillMaxWidth())
                if (picked && !state.cashOnly) {
                    Field(
                        stringResource(Res.string.form_days),
                        state.days[code].orEmpty(),
                        { onEvent(CustomerFormEvent.DaysChanged(code, it)) },
                        modifier = Modifier.padding(start = 16.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            }
        }
        ToggleRow(
            stringResource(Res.string.form_cash_only),
            state.cashOnly,
            { onEvent(CustomerFormEvent.CashOnlyChanged(it)) },
            note = stringResource(Res.string.form_cash_only_note),
        )
        ToggleRow(
            stringResource(Res.string.form_needs_lpo),
            state.needsLpo,
            { onEvent(CustomerFormEvent.NeedsLpoChanged(it)) },
            note = stringResource(Res.string.form_needs_lpo_note),
        )
        if (state.isEdit) {
            ToggleRow(
                stringResource(Res.string.form_can_buy),
                state.active,
                { onEvent(CustomerFormEvent.ActiveChanged(it)) },
                note = stringResource(Res.string.form_can_buy_note),
            )
        }
    }
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.error != null) Say(state.error, error = true)
        PrimaryButton(
            stringResource(Res.string.form_save),
            onClick = { onEvent(CustomerFormEvent.Save) },
            enabled = !state.saving && !state.deleting,
        )
        if (state.canDelete) {
            DeleteButton(
                name = state.name,
                asking = state.asking,
                busy = state.deleting,
                onAsk = { onEvent(CustomerFormEvent.AskDelete) },
                onConfirm = { onEvent(CustomerFormEvent.Delete) },
                onDismiss = { onEvent(CustomerFormEvent.Dismiss) },
            )
        }
    }
}

private fun previewState(edit: Boolean) = CustomerFormState(
    isEdit = edit,
    loading = false,
    name = if (edit) "Area 47 Superstore" else "",
    area = if (edit) "Area 47, Lilongwe" else "",
    phone = if (edit) "0991234567" else "",
    address = if (edit) "Shop 4, Area 47 market" else "",
    city = if (edit) "Lilongwe" else "",
    tpin = if (edit) "20123456" else "",
    regNo = if (edit) "BN12345" else "",
    channel = if (edit) "supermarket" else "",
    availableCompanies = listOf("LV", "ZT", "ZF", "GL"),
    selected = if (edit) setOf("LV", "ZT") else emptySet(),
    days = mapOf("LV" to "30", "ZT" to "14", "ZF" to "30", "GL" to "30"),
    needsLpo = edit,
    canDelete = edit,
)

@Preview
@Composable
private fun CustomerFormAddPreview() {
    SalesTheme { CustomerFormScreen(previewState(false), {}, {}) }
}

@Preview
@Composable
private fun CustomerFormEditPreview() {
    SalesTheme { CustomerFormScreen(previewState(true), {}, {}) }
}
