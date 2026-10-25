package com.salesapp.ui.customers

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.isValidPhone
import com.salesapp.core.normalisePhoneInput
import com.salesapp.data.AppState
import com.salesapp.data.Customer
import com.salesapp.data.canDeleteCustomers
import com.salesapp.data.CustomerCompany
import com.salesapp.data.CustomerRequest
import com.salesapp.data.ListsRepository
import com.salesapp.resources.delete_done
import com.salesapp.resources.delete_kept
import com.salesapp.resources.Res
import com.salesapp.resources.form_added
import com.salesapp.resources.form_err_company
import com.salesapp.resources.form_err_days
import com.salesapp.resources.form_err_name
import com.salesapp.resources.form_err_phone
import com.salesapp.resources.form_err_type
import com.salesapp.resources.form_saved
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

val CustomerChannels = listOf("supermarket", "wholesaler", "minibus", "upcountry")

data class CustomerFormState(
    val isEdit: Boolean = false,
    val loading: Boolean = true,
    val loadError: String? = null,
    val name: String = "",
    val area: String = "",
    val phone: String = "",
    val address: String = "",
    val city: String = "",
    val tpin: String = "",
    val regNo: String = "",
    val channel: String = "",
    // Company codes the person may pick, and the ones picked.
    val availableCompanies: List<String> = emptyList(),
    val selected: Set<String> = emptySet(),
    // Days to pay as typed, by company code.
    val days: Map<String, String> = emptyMap(),
    val cashOnly: Boolean = false,
    val needsLpo: Boolean = false,
    val active: Boolean = true,
    val error: String? = null,
    val saving: Boolean = false,
    // Only on an existing customer the server also says this person may change.
    val canDelete: Boolean = false,
    val asking: Boolean = false,
    val deleting: Boolean = false,
)

sealed interface CustomerFormEvent {
    data object Retry : CustomerFormEvent
    data class NameChanged(val value: String) : CustomerFormEvent
    data class AreaChanged(val value: String) : CustomerFormEvent
    data class PhoneChanged(val value: String) : CustomerFormEvent
    data class AddressChanged(val value: String) : CustomerFormEvent
    data class CityChanged(val value: String) : CustomerFormEvent
    data class TpinChanged(val value: String) : CustomerFormEvent
    data class RegNoChanged(val value: String) : CustomerFormEvent
    data class ChannelPicked(val code: String) : CustomerFormEvent
    data class CompanyToggled(val code: String) : CustomerFormEvent
    data class DaysChanged(val code: String, val value: String) : CustomerFormEvent
    data class CashOnlyChanged(val value: Boolean) : CustomerFormEvent
    data class NeedsLpoChanged(val value: Boolean) : CustomerFormEvent
    data class ActiveChanged(val value: Boolean) : CustomerFormEvent
    data object Save : CustomerFormEvent
    data object AskDelete : CustomerFormEvent
    data object Dismiss : CustomerFormEvent
    data object Delete : CustomerFormEvent
}

sealed interface CustomerFormEffect {
    data object Done : CustomerFormEffect
}

class CustomerFormViewModel(
    private val id: Int?,
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(CustomerFormState(isEdit = id != null))
    val state: StateFlow<CustomerFormState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CustomerFormEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<CustomerFormEffect> = _effects.asSharedFlow()

    private var original: Customer? = null

    // A customer nothing refers to goes; one with orders or a balance keeps its name on them.
    private fun delete() {
        val customerId = id ?: return
        val s = _state.value
        if (s.deleting || !s.canDelete) return
        _state.update { it.copy(deleting = true, asking = false, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.deleteCustomer(customerId)
                app.message = if (result.keptForHistory) {
                    getString(Res.string.delete_kept, s.name)
                } else {
                    getString(Res.string.delete_done, s.name)
                }
                _effects.tryEmit(CustomerFormEffect.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(deleting = false, error = app.handleApiError(e)) }
            }
        }
    }

    init {
        load()
    }

    fun onEvent(event: CustomerFormEvent) {
        when (event) {
            CustomerFormEvent.Retry -> load()
            is CustomerFormEvent.NameChanged -> edit { it.copy(name = event.value) }
            is CustomerFormEvent.AreaChanged -> edit { it.copy(area = event.value) }
            is CustomerFormEvent.PhoneChanged -> edit { it.copy(phone = event.value) }
            is CustomerFormEvent.AddressChanged -> edit { it.copy(address = event.value.take(200)) }
            is CustomerFormEvent.CityChanged -> edit { it.copy(city = event.value.take(60)) }
            is CustomerFormEvent.TpinChanged -> edit { it.copy(tpin = event.value.filter(Char::isDigit).take(12)) }
            is CustomerFormEvent.RegNoChanged -> edit { it.copy(regNo = event.value.take(30)) }
            is CustomerFormEvent.ChannelPicked -> edit { it.copy(channel = event.code) }
            is CustomerFormEvent.CompanyToggled -> edit {
                it.copy(selected = if (event.code in it.selected) it.selected - event.code else it.selected + event.code)
            }
            is CustomerFormEvent.DaysChanged -> edit { it.copy(days = it.days + (event.code to event.value.filter(Char::isDigit).take(3))) }
            is CustomerFormEvent.CashOnlyChanged -> edit { it.copy(cashOnly = event.value) }
            is CustomerFormEvent.NeedsLpoChanged -> edit { it.copy(needsLpo = event.value) }
            CustomerFormEvent.AskDelete -> if (_state.value.canDelete) _state.update { it.copy(asking = true) }
            CustomerFormEvent.Dismiss -> _state.update { it.copy(asking = false) }
            CustomerFormEvent.Delete -> delete()
            is CustomerFormEvent.ActiveChanged -> edit { it.copy(active = event.value) }
            CustomerFormEvent.Save -> save()
        }
    }

    private fun edit(change: (CustomerFormState) -> CustomerFormState) {
        _state.update { change(it).copy(error = null) }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val user = app.session.user.value
                // Everybody but the admin works with their own companies only.
                val available = if (user?.role == "admin") {
                    repository.companies().map { it.code }
                } else {
                    user?.companies.orEmpty()
                }
                val customer = id?.let { repository.customer(it) }
                original = customer
                _state.update {
                    if (customer == null) {
                        it.copy(
                            loading = false,
                            availableCompanies = available,
                            days = available.associateWith { "30" },
                        )
                    } else {
                        val days = available.associateWith { code ->
                            val stored = customer.companies.firstOrNull { c -> c.code == code }?.paymentTermsDays
                            if (stored == null || customer.cashOnly) "30" else stored.toString()
                        }
                        it.copy(
                            loading = false,
                            availableCompanies = available,
                            name = customer.name,
                            area = customer.area,
                            phone = customer.phone.orEmpty(),
                            address = customer.address,
                            city = customer.city,
                            tpin = customer.tpin,
                            regNo = customer.regNo,
                            channel = customer.channel,
                            selected = customer.companies.map { c -> c.code }.filter { c -> c in available }.toSet(),
                            days = days,
                            cashOnly = customer.cashOnly,
                            needsLpo = customer.needsLpo,
                            active = customer.active,
                            // The server's own canEdit on this customer decides, not the role alone.
                            canDelete = app.user?.canDeleteCustomers(customer) == true,
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = app.handleApiError(e)) }
            }
        }
    }

    private fun save() {
        val s = _state.value
        if (s.saving || s.loading) return
        // Mark as saving at once so a double tap cannot save twice.
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val name = s.name.trim()
            val phone = normalisePhoneInput(s.phone)
            val chosen = s.availableCompanies.filter { it in s.selected }
            val dayValues = chosen.map { s.days[it]?.toIntOrNull() }
            val problem = when {
                name.isEmpty() -> getString(Res.string.form_err_name)
                s.channel.isEmpty() -> getString(Res.string.form_err_type)
                chosen.isEmpty() -> getString(Res.string.form_err_company)
                !s.cashOnly && dayValues.any { it == null || it !in 0..365 } -> getString(Res.string.form_err_days)
                phone.isNotEmpty() && !isValidPhone(phone) -> getString(Res.string.form_err_phone)
                else -> null
            }
            if (problem != null) {
                _state.update { it.copy(saving = false, error = problem) }
                return@launch
            }
            val companies = chosen.mapIndexed { i, code -> CustomerCompany(code, if (s.cashOnly) 0 else dayValues[i] ?: 0) }
            try {
                val before = original
                if (before == null) {
                    repository.createCustomer(
                        CustomerRequest(
                            name = name,
                            channel = s.channel,
                            area = s.area.trim(),
                            phone = phone.ifEmpty { null },
                            address = s.address.trim(),
                            city = s.city.trim(),
                            tpin = s.tpin.trim(),
                            regNo = s.regNo.trim(),
                            needsLpo = s.needsLpo,
                            cashOnly = s.cashOnly,
                            companies = companies,
                        ),
                    )
                    app.message = getString(Res.string.form_added, name)
                } else {
                    val hidden = before.companies.filter { it.code !in s.availableCompanies }
                    val visibleBefore = before.companies.filter { it.code in s.availableCompanies }
                    val changed = companies.toSet() != visibleBefore.map { c ->
                        if (s.cashOnly) c.copy(paymentTermsDays = 0) else c
                    }.toSet()
                    repository.updateCustomer(
                        before.id,
                        CustomerRequest(
                            name = name,
                            channel = s.channel,
                            area = s.area.trim(),
                            phone = phone,
                            address = s.address.trim(),
                            city = s.city.trim(),
                            tpin = s.tpin.trim(),
                            regNo = s.regNo.trim(),
                            needsLpo = s.needsLpo,
                            cashOnly = s.cashOnly,
                            companies = if (changed) companies + hidden else null,
                            active = s.active,
                        ),
                    )
                    app.message = getString(Res.string.form_saved)
                }
                _state.update { it.copy(saving = false) }
                _effects.tryEmit(CustomerFormEffect.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = app.handleApiError(e)) }
            }
        }
    }
}
