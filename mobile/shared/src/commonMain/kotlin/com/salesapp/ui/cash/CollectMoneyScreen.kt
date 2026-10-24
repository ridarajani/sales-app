package com.salesapp.ui.cash

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.data.Allocation
import com.salesapp.data.CollectCompany
import com.salesapp.data.CollectCustomer
import com.salesapp.data.CollectInfo
import com.salesapp.data.Customer
import com.salesapp.data.OpenInvoice
import com.salesapp.data.PaymentDetail
import com.salesapp.data.User
import com.salesapp.data.isOfficeCollector
import com.salesapp.resources.Res
import com.salesapp.resources.collect_amount
import com.salesapp.resources.collect_another
import com.salesapp.resources.collect_cash_note
import com.salesapp.resources.collect_change_customer
import com.salesapp.resources.collect_owes_label
import com.salesapp.resources.collect_discount
import com.salesapp.resources.collect_done
import com.salesapp.resources.collect_how
import com.salesapp.resources.collect_in_credit
import com.salesapp.resources.collect_invoice_row
import com.salesapp.resources.collect_less
import com.salesapp.resources.collect_less_needs_invoice
import com.salesapp.resources.collect_mode_airtel
import com.salesapp.resources.collect_mode_bank
import com.salesapp.resources.collect_mode_cash
import com.salesapp.resources.collect_no_access
import com.salesapp.resources.collect_no_customers
import com.salesapp.resources.collect_no_invoice
import com.salesapp.resources.collect_no_open_invoices
import com.salesapp.resources.collect_note
import com.salesapp.resources.collect_oldest_first
import com.salesapp.resources.collect_on_account
import com.salesapp.resources.collect_opening_left
import com.salesapp.resources.collect_overdue
import com.salesapp.resources.collect_owes_now
import com.salesapp.resources.collect_proof
import com.salesapp.resources.collect_proof_bank
import com.salesapp.resources.collect_receipt
import com.salesapp.resources.collect_received_by
import com.salesapp.resources.collect_received_by_hint
import com.salesapp.resources.collect_reference
import com.salesapp.resources.collect_save
import com.salesapp.resources.collect_saved
import com.salesapp.resources.collect_saving
import com.salesapp.resources.collect_search
import com.salesapp.resources.collect_signature
import com.salesapp.resources.collect_signature_hint
import com.salesapp.resources.collect_title
import com.salesapp.resources.collect_title_office
import com.salesapp.resources.collect_went_invoice
import com.salesapp.resources.collect_went_on
import com.salesapp.resources.collect_went_opening
import com.salesapp.resources.collect_which_company
import com.salesapp.resources.collect_which_invoice
import com.salesapp.resources.collect_who
import com.salesapp.resources.collect_wht
import com.salesapp.resources.collect_wht_certificate
import com.salesapp.resources.delivery_signature_clear
import com.salesapp.ui.components.Badge
import com.salesapp.ui.components.BadgeTone
import com.salesapp.ui.components.ChoiceButton
import com.salesapp.ui.components.ChoiceColumn
import com.salesapp.ui.components.CompanyDot
import com.salesapp.ui.components.EmptyState
import com.salesapp.ui.components.ErrorState
import com.salesapp.ui.components.Field
import com.salesapp.ui.components.ListCard
import com.salesapp.ui.components.LoadingState
import com.salesapp.ui.components.MwkField
import com.salesapp.ui.components.PrimaryButton
import com.salesapp.ui.components.Say
import com.salesapp.ui.components.SearchField
import com.salesapp.ui.components.SecondaryButton
import com.salesapp.ui.components.SectionLabel
import com.salesapp.ui.components.SignaturePad
import com.salesapp.ui.components.ToggleRow
import com.salesapp.ui.components.TopBar
import com.salesapp.ui.theme.SalesTheme
import com.salesapp.ui.theme.caption
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CollectMoneyPage(
    customerId: Int? = null,
    companyCode: String? = null,
    invoiceId: Int? = null,
    onBack: () -> Unit,
    viewModel: CollectMoneyViewModel = koinViewModel { parametersOf(customerId, companyCode, invoiceId) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    CollectMoneyScreen(state, viewModel::onEvent, onBack)
}

// One screen for a payment: pick the shop, the company, the invoice, how they paid and how much,
// then the name and signature. The office calls it "Record a payment".
@Composable
fun CollectMoneyScreen(state: CollectState, onEvent: (CollectEvent) -> Unit, onBack: () -> Unit) {
    val office = state.user?.isOfficeCollector() == true
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(stringResource(if (office) Res.string.collect_title_office else Res.string.collect_title), onBack = onBack)
        when {
            !state.allowed -> Say(stringResource(Res.string.collect_no_access), error = true, modifier = Modifier.padding(16.dp))
            state.saved != null -> Saved(state.saved, onEvent, onBack)
            state.loading -> LoadingState()
            state.info != null -> Form(state, onEvent)
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(CollectEvent.Retry) })
            else -> PickCustomer(state, onEvent)
        }
    }
}

// Step 1: who is paying.
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.PickCustomer(state: CollectState, onEvent: (CollectEvent) -> Unit) {
    SectionLabel(stringResource(Res.string.collect_who), modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    SearchField(
        value = state.query,
        onValueChange = { onEvent(CollectEvent.QueryChanged(it)) },
        placeholder = stringResource(Res.string.collect_search),
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.searching && state.customers.isEmpty() -> item { LoadingState() }
            state.customers.isEmpty() -> item { EmptyState(stringResource(Res.string.collect_no_customers)) }
            else -> items(state.customers, key = { it.id }) { customer ->
                ListCard(onClick = { onEvent(CollectEvent.CustomerChosen(customer.id)) }) {
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(customer.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleSmall)
                        if (customer.area.isNotBlank()) {
                            Text(customer.area, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

// Steps 2 to 5, all on one screen.
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Form(state: CollectState, onEvent: (CollectEvent) -> Unit) {
    val info = state.info ?: return
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(info.customer.name, color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                if (info.customer.area.isNotBlank()) {
                    Text(info.customer.area, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        // Which company's money this is.
        if (info.companies.size > 1) {
            SectionLabel(stringResource(Res.string.collect_which_company))
            ChoiceColumn {
                info.companies.forEach { company ->
                    ChoiceButton(
                        text = "${company.name} · ${formatMwk(company.owes.toLong())}",
                        selected = state.companyCode == company.code,
                        onClick = { onEvent(CollectEvent.CompanyChosen(company.code)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val company = state.company
        if (company != null) {
            Owes(company)
            SectionLabel(stringResource(Res.string.collect_which_invoice))
            if (company.invoices.isEmpty()) {
                Say(stringResource(Res.string.collect_no_open_invoices))
            } else {
                ChoiceColumn {
                    company.invoices.forEach { invoice -> InvoiceChoice(invoice, state, onEvent) }
                    ChoiceButton(
                        text = stringResource(Res.string.collect_no_invoice),
                        selected = state.invoiceId == null,
                        onClick = { onEvent(CollectEvent.InvoiceChosen(null)) },
                        enabled = !state.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            SectionLabel(stringResource(Res.string.collect_how))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    "cash" to stringResource(Res.string.collect_mode_cash),
                    "bank" to stringResource(Res.string.collect_mode_bank),
                    "airtel" to stringResource(Res.string.collect_mode_airtel),
                ).forEach { (code, label) ->
                    ChoiceButton(
                        text = label,
                        selected = state.mode == code,
                        onClick = { onEvent(CollectEvent.ModeChosen(code)) },
                        enabled = !state.busy,
                    )
                }
            }
            MwkField(
                label = stringResource(Res.string.collect_amount),
                amount = state.amount,
                onAmountChange = { onEvent(CollectEvent.AmountChanged(it)) },
            )
            if (state.needsReference || state.mode == "airtel") {
                Field(
                    label = stringResource(Res.string.collect_reference),
                    value = state.reference,
                    onValueChange = { onEvent(CollectEvent.ReferenceChanged(it)) },
                    enabled = !state.busy,
                )
            }
            if (state.mode != "cash") {
                PhotoField(
                    label = stringResource(if (state.mode == "bank") Res.string.collect_proof_bank else Res.string.collect_proof),
                    photo = state.proof,
                    cameraAvailable = state.cameraAvailable,
                    busy = state.busy,
                    onTake = { onEvent(CollectEvent.TakeProof) },
                    onRemove = { onEvent(CollectEvent.RemoveProof) },
                )
            }
            // A discount or withholding tax is unusual, so it stays folded away.
            ToggleRow(
                label = stringResource(Res.string.collect_less),
                checked = state.showLess,
                onCheckedChange = { onEvent(CollectEvent.ToggleLess) },
                note = if (state.canUseLess) null else stringResource(Res.string.collect_less_needs_invoice),
            )
            if (state.showLess && state.canUseLess) {
                MwkField(
                    label = stringResource(Res.string.collect_discount),
                    amount = state.discount,
                    onAmountChange = { onEvent(CollectEvent.DiscountChanged(it)) },
                )
                MwkField(
                    label = stringResource(Res.string.collect_wht),
                    amount = state.wht,
                    onAmountChange = { onEvent(CollectEvent.WhtChanged(it)) },
                )
                Field(
                    label = stringResource(Res.string.collect_wht_certificate),
                    value = state.whtCertificate,
                    onValueChange = { onEvent(CollectEvent.CertificateChanged(it)) },
                    enabled = !state.busy,
                )
            }
            if (state.needsSignature) {
                Field(
                    label = stringResource(Res.string.collect_received_by),
                    value = state.receivedBy,
                    onValueChange = { onEvent(CollectEvent.ReceivedByChanged(it)) },
                    placeholder = stringResource(Res.string.collect_received_by_hint),
                    enabled = !state.busy,
                )
                SectionLabel(stringResource(Res.string.collect_signature))
                SignaturePad(
                    strokes = state.strokes,
                    onStrokeDone = { onEvent(CollectEvent.StrokeDone(it)) },
                    onSize = { onEvent(CollectEvent.PadSized(it)) },
                    hint = stringResource(Res.string.collect_signature_hint),
                    enabled = !state.busy,
                )
                SecondaryButton(
                    stringResource(Res.string.delivery_signature_clear),
                    onClick = { onEvent(CollectEvent.ClearSignature) },
                    enabled = !state.busy && state.hasSignature,
                )
            }
            Field(
                label = stringResource(Res.string.collect_note),
                value = state.note,
                onValueChange = { onEvent(CollectEvent.NoteChanged(it)) },
                enabled = !state.busy,
            )
            Text(
                stringResource(Res.string.collect_oldest_first),
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
            if (state.mode == "cash") {
                Text(stringResource(Res.string.collect_cash_note), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            state.actionError?.let { Say(it, error = true) }
            SecondaryButton(
                stringResource(Res.string.collect_change_customer),
                onClick = { onEvent(CollectEvent.ChangeCustomer) },
                enabled = !state.busy,
            )
        }
    }
    PrimaryButton(
        stringResource(if (state.busy) Res.string.collect_saving else Res.string.collect_save),
        onClick = { onEvent(CollectEvent.Save) },
        enabled = !state.busy && state.companyCode != null,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun Owes(company: CollectCompany) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        BigMoney(stringResource(Res.string.collect_owes_label), company.owes, warn = company.owes > 0)
        if (company.openingLeft > 0) {
            Text(
                stringResource(Res.string.collect_opening_left, formatMwk(company.openingLeft.toLong())),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun InvoiceChoice(invoice: OpenInvoice, state: CollectState, onEvent: (CollectEvent) -> Unit) {
    ChoiceButton(
        text = stringResource(Res.string.collect_invoice_row, invoice.number, formatMwk(invoice.balance.toLong())) +
            if (invoice.overdue) " · ${stringResource(Res.string.collect_overdue)}" else "",
        selected = state.invoiceId == invoice.id,
        onClick = { onEvent(CollectEvent.InvoiceChosen(invoice.id)) },
        enabled = !state.busy,
        modifier = Modifier.fillMaxWidth(),
    )
}

// What the payment did, once it is saved.
@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Saved(
    saved: PaymentDetail,
    onEvent: (CollectEvent) -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Say(stringResource(Res.string.collect_saved))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(Res.string.collect_receipt, saved.number),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(saved.customerName, color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
                CompanyDot(saved.companyCode)
                Text(
                    formatMwk(saved.amount.toLong()),
                    color = scheme.onSurface,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        SectionLabel(stringResource(Res.string.collect_went_on))
        ListCard(onClick = null) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                saved.allocations.forEach { allocation -> AllocationRow(allocation) }
                if (saved.onAccount > 0) {
                    Text(
                        stringResource(Res.string.collect_on_account, formatMwk(saved.onAccount.toLong())),
                        color = scheme.onSurface,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
        // A balance below 0 means the shop has paid more than it owes.
        Text(
            if (saved.owesNow < 0) {
                stringResource(Res.string.collect_in_credit, formatMwk(-saved.owesNow.toLong()))
            } else {
                stringResource(Res.string.collect_owes_now, formatMwk(saved.owesNow.toLong()))
            },
            color = scheme.onSurface,
            style = MaterialTheme.typography.titleSmall,
        )
    }
    Row(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        SecondaryButton(
            stringResource(Res.string.collect_another),
            onClick = { onEvent(CollectEvent.Another) },
            modifier = Modifier.weight(1f),
        )
        PrimaryButton(stringResource(Res.string.collect_done), onClick = onBack, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AllocationRow(allocation: Allocation) {
    val scheme = MaterialTheme.colorScheme
    val amount = formatMwk(allocation.amount.toLong())
    Text(
        allocation.invoiceNumber?.let { stringResource(Res.string.collect_went_invoice, it, amount) }
            ?: stringResource(Res.string.collect_went_opening, amount),
        color = scheme.onSurface,
        style = MaterialTheme.typography.bodyMedium,
    )
}

private val previewSalesman = User(4, "Chimwemwe Banda", "0991000004", "salesman", companies = listOf("LV", "ZT"))

private val previewInfo = CollectInfo(
    customer = CollectCustomer(id = 1, name = "Area 47 Superstore", area = "Area 47"),
    companies = listOf(
        CollectCompany(
            code = "LV",
            name = "Lucky Ventures",
            owes = 1_164_000.0,
            openingLeft = 600_000.0,
            invoices = listOf(
                OpenInvoice(id = 3, number = "LV-INV-000014", invoiceDate = "2026-10-23", dueDate = "2026-11-22", total = 564_000.0, balance = 564_000.0),
                OpenInvoice(id = 1, number = "LV-INV-000009", invoiceDate = "2026-09-20", dueDate = "2026-10-20", total = 300_000.0, balance = 120_000.0, overdue = true),
            ),
        ),
        CollectCompany(code = "ZT", name = "Zoona Trading", owes = 96_000.0),
    ),
)

@Preview
@Composable
private fun CollectPickCustomerPreview() {
    SalesTheme {
        CollectMoneyScreen(
            state = CollectState(
                user = previewSalesman,
                customers = listOf(
                    Customer(id = 1, name = "Area 47 Superstore", area = "Area 47"),
                    Customer(id = 2, name = "Kanengo Wholesalers", area = "Kanengo"),
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun CollectFormPreview() {
    SalesTheme {
        CollectMoneyScreen(
            state = CollectState(
                user = previewSalesman,
                info = previewInfo,
                companyCode = "LV",
                invoiceId = 3,
                mode = "cash",
                amount = 564_000,
                receivedBy = "Grace Banda",
                cameraAvailable = true,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun CollectAirtelPreview() {
    SalesTheme {
        CollectMoneyScreen(
            state = CollectState(
                user = previewSalesman,
                info = previewInfo,
                companyCode = "LV",
                invoiceId = 3,
                mode = "airtel",
                amount = 200_000,
                reference = "PP251023.1420.A12345",
                showLess = true,
                discount = 10_000,
                cameraAvailable = true,
            ),
            onEvent = {},
            onBack = {},
        )
    }
}

@Preview
@Composable
private fun CollectSavedPreview() {
    SalesTheme {
        CollectMoneyScreen(
            state = CollectState(
                user = previewSalesman,
                info = previewInfo,
                companyCode = "LV",
                saved = PaymentDetail(
                    id = 1,
                    number = "LV-RCT-000007",
                    companyCode = "LV",
                    customerName = "Area 47 Superstore",
                    mode = "cash",
                    amount = 700_000.0,
                    receivedBy = "Grace Banda",
                    allocations = listOf(
                        Allocation(invoiceId = 3, invoiceNumber = "LV-INV-000014", amount = 564_000.0),
                        Allocation(invoiceId = null, invoiceNumber = null, amount = 136_000.0),
                    ),
                    owesNow = 464_000.0,
                ),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
