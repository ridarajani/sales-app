package com.salesapp.ui.customers

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.salesapp.core.formatIsoDate
import com.salesapp.core.formatMwk
import com.salesapp.core.formatPhone
import com.salesapp.data.Customer
import com.salesapp.data.CustomerCompany
import com.salesapp.data.OpeningBalance
import com.salesapp.data.User
import com.salesapp.data.canCorrectOpeningBalance
import com.salesapp.data.canEditCustomers
import com.salesapp.data.canEnterOpeningBalance
import com.salesapp.data.canSeeOpeningBalances
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.customer_area
import com.salesapp.resources.none_placeholder
import com.salesapp.resources.customer_balance_admin_only
import com.salesapp.resources.customer_balance_as_of
import com.salesapp.resources.customer_balance_entered_by
import com.salesapp.resources.customer_balance_not_entered
import com.salesapp.resources.customer_companies
import com.salesapp.resources.customer_correct_balance
import com.salesapp.resources.customer_edit
import com.salesapp.resources.customer_enter_balance
import com.salesapp.resources.customer_opening_balance
import com.salesapp.resources.customer_pays_cash
import com.salesapp.resources.customer_pays_in_days
import com.salesapp.resources.customer_pays_in_one_day
import com.salesapp.resources.customer_address
import com.salesapp.resources.customer_city
import com.salesapp.resources.customer_phone
import com.salesapp.resources.customer_reg_no
import com.salesapp.resources.customer_tpin
import com.salesapp.resources.customer_switch_off
import com.salesapp.resources.customer_switch_off_text
import com.salesapp.resources.customer_switch_off_title
import com.salesapp.resources.customer_switch_on
import com.salesapp.resources.customer_switch_on_text
import com.salesapp.resources.customer_switch_on_title
import com.salesapp.ui.components.Avatar
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
import com.salesapp.ui.theme.companyColor
import org.jetbrains.compose.resources.stringResource
import androidx.compose.ui.tooling.preview.Preview
import org.koin.compose.viewmodel.koinViewModel
import org.koin.core.parameter.parametersOf

@Composable
fun CustomerDetailPage(
    id: Int,
    onBack: () -> Unit,
    onEdit: (Int) -> Unit,
    onEnterBalance: (customerId: Int, companyCode: String) -> Unit,
    viewModel: CustomerDetailViewModel = koinViewModel { parametersOf(id) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.effects.collect { effect ->
            when (effect) {
                is CustomerDetailEffect.OpenForm -> onEdit(effect.id)
                is CustomerDetailEffect.OpenBalance -> onEnterBalance(effect.customerId, effect.companyCode)
            }
        }
    }
    LifecycleResumeEffect(viewModel) {
        viewModel.onEvent(CustomerDetailEvent.Refresh)
        onPauseOrDispose {}
    }
    CustomerDetailScreen(state, viewModel::onEvent, onBack)
}

// Everything about one customer, with buttons to edit or switch off for people who may.
@Composable
fun CustomerDetailScreen(state: CustomerDetailState, onEvent: (CustomerDetailEvent) -> Unit, onBack: () -> Unit) {
    val customer = state.customer
    val user = state.user
    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(customer?.name ?: "", onBack = onBack)
        when {
            customer != null && user != null -> {
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    state.actionError?.let { Say(it, error = true) }
                    Header(customer)
                    Section {
                        DetailRow(stringResource(Res.string.customer_area), customer.area)
                        DetailRow(stringResource(Res.string.customer_phone), formatPhone(customer.phone))
                        DetailRow(stringResource(Res.string.customer_address), customer.address)
                        DetailRow(stringResource(Res.string.customer_city), customer.city)
                        DetailRow(stringResource(Res.string.customer_tpin), customer.tpin)
                        DetailRow(stringResource(Res.string.customer_reg_no), customer.regNo)
                    }
                    SectionTitle(stringResource(Res.string.customer_companies))
                    Section {
                        customer.companies.forEach { CompanyTerms(it, customer.cashOnly) }
                    }
                    if (user.canSeeOpeningBalances()) {
                        SectionTitle(stringResource(Res.string.customer_opening_balance))
                        customer.companies.forEach { company ->
                            BalanceCard(
                                company = company,
                                balance = state.balances.firstOrNull { it.companyCode == company.code },
                                user = user,
                                onOpen = { onEvent(CustomerDetailEvent.EnterBalance(company.code)) },
                            )
                        }
                    }
                }
                if (user.canEditCustomers(customer)) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        PrimaryButton(
                            stringResource(Res.string.customer_edit),
                            onClick = { onEvent(CustomerDetailEvent.Edit) },
                            enabled = !state.busy,
                            modifier = Modifier.weight(1f),
                        )
                        if (customer.active) {
                            DangerButton(
                                stringResource(Res.string.customer_switch_off),
                                onClick = { onEvent(CustomerDetailEvent.SwitchTapped) },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            )
                        } else {
                            SecondaryButton(
                                stringResource(Res.string.customer_switch_on),
                                onClick = { onEvent(CustomerDetailEvent.SwitchTapped) },
                                enabled = !state.busy,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                if (state.confirmSwitch) {
                    val off = customer.active
                    ConfirmDialog(
                        title = stringResource(if (off) Res.string.customer_switch_off_title else Res.string.customer_switch_on_title, customer.name),
                        text = stringResource(if (off) Res.string.customer_switch_off_text else Res.string.customer_switch_on_text),
                        confirmLabel = stringResource(if (off) Res.string.customer_switch_off else Res.string.customer_switch_on),
                        onConfirm = { onEvent(CustomerDetailEvent.SwitchConfirmed) },
                        onDismiss = { onEvent(CustomerDetailEvent.SwitchDismissed) },
                        danger = off,
                    )
                }
            }
            state.error != null -> ErrorState(state.error, onRetry = { onEvent(CustomerDetailEvent.Retry) })
            else -> LoadingState()
        }
    }
}

@Composable
private fun Header(customer: Customer) {
    ListCard(onClick = null) {
        Avatar(customer.name, size = 56.dp)
        Column(modifier = Modifier.weight(1f).padding(start = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CustomerBadges(customer, offLabelLong = true)
            Text(customer.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium)
        }
    }
}

// A white card holding a few rows.
@Composable
private fun Section(content: @Composable () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, shape)
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) { content() }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text.uppercase(),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 4.dp),
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column {
        Text(label.uppercase(), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium)
        Text(value.ifBlank { stringResource(Res.string.none_placeholder) }, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodyMedium)
    }
}

// One company the customer buys from, and how they pay.
@Composable
private fun CompanyTerms(company: CustomerCompany, cashOnly: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(companyColor(company.code)))
        Text(companyName(company.code), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(start = 8.dp))
        val terms = when {
            cashOnly || company.paymentTermsDays == 0 -> stringResource(Res.string.customer_pays_cash)
            company.paymentTermsDays == 1 -> stringResource(Res.string.customer_pays_in_one_day)
            else -> stringResource(Res.string.customer_pays_in_days, company.paymentTermsDays)
        }
        Text(terms, color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun BalanceCard(company: CustomerCompany, balance: OpeningBalance?, user: User, onOpen: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Section {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.size(8.dp).clip(CircleShape).background(companyColor(company.code)))
            Text(companyName(company.code), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 8.dp))
        }
        if (balance == null) {
            Text(stringResource(Res.string.customer_balance_not_entered), color = scheme.onSurface, style = MaterialTheme.typography.bodyMedium)
            if (user.canEnterOpeningBalance()) {
                SecondaryButton(stringResource(Res.string.customer_enter_balance), onClick = onOpen)
            }
        } else {
            Column {
                Text(formatMwk(balance.amount), color = scheme.onSurface, style = MaterialTheme.typography.titleMedium)
                val by = if (balance.enteredBy.isBlank()) "" else ", " + stringResource(Res.string.customer_balance_entered_by, balance.enteredBy)
                Text(
                    stringResource(Res.string.customer_balance_as_of, formatIsoDate(balance.asOf)) + by,
                    color = scheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (user.canCorrectOpeningBalance()) {
                SecondaryButton(stringResource(Res.string.customer_correct_balance), onClick = onOpen)
            } else {
                Text(stringResource(Res.string.customer_balance_admin_only), color = scheme.onSurfaceVariant, style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Normal))
            }
        }
    }
}

@Preview
@Composable
private fun CustomerDetailPreview() {
    SalesTheme {
        CustomerDetailScreen(
            state = CustomerDetailState(
                loading = false,
                customer = Customer(
                    id = 1,
                    name = "Area 47 Superstore",
                    channel = "supermarket",
                    area = "Area 47, Lilongwe",
                    phone = "0991234567",
                    address = "Shop 4, Area 47 market",
                    city = "Lilongwe",
                    tpin = "20123456",
                    regNo = "BN12345",
                    needsLpo = true,
                    companies = listOf(CustomerCompany("LV", 30), CustomerCompany("ZT", 14)),
                ),
                balances = listOf(OpeningBalance(1, "Area 47 Superstore", "LV", 1_250_000, "2026-10-20", "Grace Banda", "")),
                user = User(1, "Admin", "0990000000", "admin", listOf("LV", "ZT", "ZF", "GL")),
            ),
            onEvent = {},
            onBack = {},
        )
    }
}
