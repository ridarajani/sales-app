package com.salesapp.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.isValidPhone
import com.salesapp.core.normalisePhoneInput
import com.salesapp.data.AppState
import com.salesapp.data.CompanyDetails
import com.salesapp.data.Optional
import com.salesapp.data.companyChange
import com.salesapp.data.ListsRepository
import com.salesapp.data.canEditCompanies
import com.salesapp.resources.Res
import com.salesapp.resources.catalog_saved
import com.salesapp.resources.company_err_email
import com.salesapp.resources.company_err_name
import com.salesapp.resources.company_err_phone
import com.salesapp.resources.company_err_tpin
import com.salesapp.resources.company_err_vat
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

data class CompanyFormState(
    val code: String = "",
    val readOnly: Boolean = true,
    val loading: Boolean = true,
    val loadError: String? = null,
    val title: String = "",
    val name: String = "",
    val tpin: String = "",
    val vat: String = "",
    val phone: String = "",
    val email: String = "",
    val address: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface CompanyFormEvent {
    data class Name(val value: String) : CompanyFormEvent
    data class Tpin(val value: String) : CompanyFormEvent
    data class Vat(val value: String) : CompanyFormEvent
    data class Phone(val value: String) : CompanyFormEvent
    data class Email(val value: String) : CompanyFormEvent
    data class Address(val value: String) : CompanyFormEvent
    data object Save : CompanyFormEvent
    data object Retry : CompanyFormEvent
}

class CompanyFormViewModel(
    private val code: String,
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CompanyFormState(code = code, readOnly = app.session.user.value?.canEditCompanies() != true),
    )
    val state: StateFlow<CompanyFormState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FormEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<FormEffect> = _effects.asSharedFlow()

    private var loaded: CompanyDetails? = null

    init {
        load()
    }

    fun onEvent(event: CompanyFormEvent) {
        when (event) {
            is CompanyFormEvent.Name -> _state.update { it.copy(name = event.value, error = null) }
            is CompanyFormEvent.Tpin -> _state.update { it.copy(tpin = event.value.filter { c -> c.isDigit() }.take(12), error = null) }
            is CompanyFormEvent.Vat -> _state.update { it.copy(vat = event.value.filter { c -> c.isDigit() || c == '.' || c == ',' }.take(6), error = null) }
            is CompanyFormEvent.Phone -> _state.update { it.copy(phone = event.value.filter { c -> c.isDigit() || c == '+' || c == ' ' || c == '-' }, error = null) }
            is CompanyFormEvent.Email -> _state.update { it.copy(email = event.value.trim(), error = null) }
            is CompanyFormEvent.Address -> _state.update { it.copy(address = event.value, error = null) }
            CompanyFormEvent.Save -> save()
            CompanyFormEvent.Retry -> load()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val d = repository.companyDetails(code)
                loaded = d
                _state.update {
                    it.copy(
                        loading = false,
                        title = d.name,
                        name = d.name,
                        tpin = d.tpin.orEmpty(),
                        vat = d.vatRate?.let { v -> if (v % 1.0 == 0.0) v.toLong().toString() else v.toString() }.orEmpty(),
                        phone = d.phone.orEmpty(),
                        email = d.email.orEmpty(),
                        address = d.address.orEmpty(),
                    )
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
        if (s.busy || s.readOnly) return
        // Mark as saving at once so a double tap cannot save twice.
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val vat = s.vat.replace(',', '.').toDoubleOrNull()
            val phone = normalisePhoneInput(s.phone)
            val problem = when {
                s.name.isBlank() -> getString(Res.string.company_err_name)
                s.tpin.isNotEmpty() && s.tpin.length !in 6..12 -> getString(Res.string.company_err_tpin)
                s.vat.isNotBlank() && (vat == null || vat < 0.0 || vat > 100.0) -> getString(Res.string.company_err_vat)
                s.email.isNotEmpty() && !Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$").matches(s.email) -> getString(Res.string.company_err_email)
                phone.isNotEmpty() && !isValidPhone(phone) -> getString(Res.string.company_err_phone)
                else -> null
            }
            if (problem != null) {
                _state.update { it.copy(busy = false, error = problem) }
                return@launch
            }
            try {
                val old = loaded
                fun <T> changed(now: T, before: T): Optional<T> = if (old == null || now != before) Optional.Present(now) else Optional.Absent
                repository.updateCompany(
                    code,
                    companyChange(
                        name = changed(s.name.trim(), old?.name.orEmpty()),
                        tpin = changed(s.tpin, old?.tpin.orEmpty()),
                        vatRate = changed(vat, old?.vatRate),
                        phone = changed(phone, normalisePhoneInput(old?.phone.orEmpty())),
                        email = changed(s.email, old?.email.orEmpty()),
                        address = changed(s.address.trim(), old?.address.orEmpty().trim()),
                    ),
                )
                app.message = getString(Res.string.catalog_saved)
                _effects.tryEmit(FormEffect.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = app.handleApiError(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
