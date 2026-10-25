package com.salesapp.ui.catalog

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.catalog_save
import com.salesapp.resources.product_brand
import com.salesapp.resources.product_code
import com.salesapp.resources.product_code_hint
import com.salesapp.resources.product_code_note
import com.salesapp.resources.product_company
import com.salesapp.resources.product_name
import com.salesapp.resources.product_pack
import com.salesapp.resources.product_pack_hint
import com.salesapp.resources.product_pack_note
import com.salesapp.resources.product_pieces
import com.salesapp.resources.product_selling
import com.salesapp.resources.product_selling_note
import com.salesapp.resources.product_vat_exempt
import com.salesapp.resources.product_vat_exempt_help
import com.salesapp.resources.product_vat_exempt_no
import com.salesapp.resources.product_vat_exempt_yes
import com.salesapp.resources.products_add
import com.salesapp.resources.products_edit
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.DeleteButton
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.ToggleRow
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun ProductFormPage(
    id: Int?,
    onBack: () -> Unit,
    onDone: () -> Unit,
    viewModel: ProductFormViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { onDone() }
    }
    ProductFormScreen(state, viewModel::onEvent, onBack)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProductFormScreen(state: ProductFormState, onEvent: (ProductFormEvent) -> Unit, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize().imePadding()) {
        TopBar(stringResource(if (state.isEdit) Res.string.products_edit else Res.string.products_add), onBack = onBack)
        when {
            state.loading -> LoadingState()
            state.loadError != null -> ErrorState(state.loadError, onRetry = { onEvent(ProductFormEvent.Retry) })
            else -> {
              Column(
                modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
              ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    SectionLabel(stringResource(Res.string.product_company))
                    ChoiceColumn {
                        state.companies.forEach { code ->
                            ChoiceButton(
                                companyName(code),
                                selected = state.company == code,
                                onClick = { onEvent(ProductFormEvent.Company(code)) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = !state.busy,
                            )
                        }
                    }
                }
                Field(stringResource(Res.string.product_brand), state.brand, { onEvent(ProductFormEvent.Brand(it)) }, enabled = !state.busy)
                Field(stringResource(Res.string.product_name), state.name, { onEvent(ProductFormEvent.Name(it)) }, enabled = !state.busy)
                Field(
                    stringResource(Res.string.product_pack),
                    state.packSize,
                    { onEvent(ProductFormEvent.Pack(it)) },
                    placeholder = stringResource(Res.string.product_pack_hint),
                    note = stringResource(Res.string.product_pack_note),
                    enabled = !state.busy,
                )
                Field(
                    stringResource(Res.string.product_pieces),
                    state.pieces,
                    { onEvent(ProductFormEvent.Pieces(it)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    enabled = !state.busy,
                )
                Field(
                    stringResource(Res.string.product_code),
                    state.code,
                    { onEvent(ProductFormEvent.Code(it)) },
                    placeholder = stringResource(Res.string.product_code_hint),
                    note = stringResource(Res.string.product_code_note),
                    enabled = !state.busy,
                )
                // Client, 24 Oct: VAT is worked out only on the lines that are not exempt.
                SectionLabel(stringResource(Res.string.product_vat_exempt))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to Res.string.product_vat_exempt_no, true to Res.string.product_vat_exempt_yes).forEach { (value, label) ->
                        ChoiceButton(
                            text = stringResource(label),
                            selected = state.vatExempt == value,
                            onClick = { onEvent(ProductFormEvent.VatExempt(value)) },
                            enabled = !state.busy,
                        )
                    }
                }
                if (state.vatExempt) {
                    Text(
                        stringResource(Res.string.product_vat_exempt_help),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (state.isEdit) {
                    ToggleRow(
                        stringResource(Res.string.product_selling),
                        checked = state.active,
                        onCheckedChange = { onEvent(ProductFormEvent.Active(it)) },
                        note = stringResource(Res.string.product_selling_note),
                    )
                }
              }
              Column(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
              ) {
                state.error?.let { Say(it, error = true) }
                PrimaryButton(
                    stringResource(Res.string.catalog_save),
                    onClick = { onEvent(ProductFormEvent.Save) },
                    enabled = !state.busy && !state.deleting,
                )
                if (state.canDelete) {
                    DeleteButton(
                        name = listOf(state.brand, state.name, state.packSize).filter { it.isNotBlank() }.joinToString(" "),
                        asking = state.asking,
                        busy = state.deleting,
                        onAsk = { onEvent(ProductFormEvent.AskDelete) },
                        onConfirm = { onEvent(ProductFormEvent.Delete) },
                        onDismiss = { onEvent(ProductFormEvent.Dismiss) },
                    )
                }
              }
            }
        }
    }
}

@Preview
@Composable
private fun ProductFormPreview() {
    SalesTheme {
        ProductFormScreen(
            state = ProductFormState(
                isEdit = true,
                companies = listOf("LV", "ZT"),
                company = "LV",
                brand = "Kerrygold",
                name = "Milk powder sachet",
                packSize = "7.5g x 400",
                pieces = "400",
                code = "LV-KG-075",
                vatExempt = true,
                canDelete = true,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
