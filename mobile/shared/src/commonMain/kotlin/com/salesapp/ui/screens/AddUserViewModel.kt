package com.salesapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.NewUserRequest
import com.salesapp.data.Role
import com.salesapp.data.UserRow
import com.salesapp.data.UsersRepository
import com.salesapp.core.isValidPhone
import com.salesapp.core.normalisePhoneInput
import com.salesapp.data.companyCodes
import com.salesapp.data.companyName
import com.salesapp.resources.Res
import com.salesapp.resources.user_add_added
import com.salesapp.resources.user_add_err_company
import com.salesapp.resources.user_add_err_job
import com.salesapp.resources.user_add_err_manager
import com.salesapp.resources.user_add_err_name
import com.salesapp.resources.user_add_err_phone
import com.salesapp.resources.user_add_err_phone_bad
import com.salesapp.resources.user_add_err_pin
import com.salesapp.ui.components.PIN_LENGTH
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

data class AddUserState(
    val isSalesManager: Boolean = false,
    // The companies of the signed-in sales manager; a salesman can only get some of these.
    val myCompanies: List<String> = emptyList(),
    val users: List<UserRow> = emptyList(),
    val name: String = "",
    val phone: String = "",
    val role: Role? = null,
    // Only set when a sales manager adds a salesman: it is always themselves.
    val managerId: Int? = null,
    // Admin or management adding a salesman: the sales manager chosen for each company.
    val companyManagers: Map<String, Int> = emptyMap(),
    val companies: Set<String> = emptySet(),
    val pin: String = "",
    val busy: Boolean = false,
    val loadingList: Boolean = true,
    val error: String? = null,
) {
    fun managersFor(code: String): List<UserRow> =
        users.filter { it.role == "sales_manager" && it.active && code in it.companies }

    val pickableCompanies: List<String>
        get() = if (role == Role.Salesman && isSalesManager) companyCodes.filter { it in myCompanies } else companyCodes

    // The chosen companies in the usual order.
    val chosenCompanies: List<String> get() = companyCodes.filter { it in companies }
}

sealed interface AddUserEvent {
    data class Name(val value: String) : AddUserEvent
    data class Phone(val value: String) : AddUserEvent
    data class RoleChosen(val role: Role) : AddUserEvent
    data class ManagerChosen(val code: String, val id: Int) : AddUserEvent
    data class CompanyToggled(val code: String) : AddUserEvent
    data class Pin(val value: String) : AddUserEvent
    data object Submit : AddUserEvent
}

sealed interface AddUserEffect {
    data object Done : AddUserEffect
}

class AddUserViewModel(
    private val repository: UsersRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state: MutableStateFlow<AddUserState>
    val state: StateFlow<AddUserState>

    private val _effects = MutableSharedFlow<AddUserEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<AddUserEffect> = _effects.asSharedFlow()

    init {
        val me = app.session.user.value
        val isSalesManager = me?.role == "sales_manager"
        _state = MutableStateFlow(
            AddUserState(
                isSalesManager = isSalesManager,
                myCompanies = me?.companies ?: emptyList(),
                users = repository.rows.value,
                role = if (isSalesManager) Role.Salesman else null,
                managerId = if (isSalesManager) me?.id else null,
            ),
        )
        state = _state.asStateFlow()
        loadList()
    }

    fun onEvent(event: AddUserEvent) {
        when (event) {
            is AddUserEvent.Name -> _state.update { it.copy(name = event.value) }
            is AddUserEvent.Phone -> _state.update {
                it.copy(phone = event.value.filter { c -> c.isDigit() || c == '+' || c == ' ' || c == '-' })
            }
            is AddUserEvent.RoleChosen -> _state.update {
                it.copy(
                    role = event.role,
                    companies = emptySet(),
                    companyManagers = emptyMap(),
                )
            }
            is AddUserEvent.ManagerChosen -> _state.update { it.copy(companyManagers = it.companyManagers + (event.code to event.id)) }
            is AddUserEvent.CompanyToggled -> _state.update {
                if (event.code in it.companies) {
                    it.copy(companies = it.companies - event.code, companyManagers = it.companyManagers - event.code)
                } else {
                    it.copy(companies = it.companies + event.code)
                }
            }
            is AddUserEvent.Pin -> _state.update { it.copy(pin = event.value.filter { c -> c.isDigit() }.take(PIN_LENGTH)) }
            AddUserEvent.Submit -> submit()
        }
    }

    // The list of sales managers comes from the server.
    private fun loadList() {
        viewModelScope.launch {
            try {
                val rows = repository.refresh()
                _state.update { it.copy(users = rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = app.handleApiError(e)) }
            } finally {
                _state.update { it.copy(loadingList = false) }
            }
        }
    }

    private fun submit() {
        val s = _state.value
        if (s.busy) return
        viewModelScope.launch {
            val r = s.role
            val problem = when {
                s.name.isBlank() -> getString(Res.string.user_add_err_name)
                s.phone.isBlank() -> getString(Res.string.user_add_err_phone)
                !isValidPhone(normalisePhoneInput(s.phone.trim())) -> getString(Res.string.user_add_err_phone_bad)
                r == null -> getString(Res.string.user_add_err_job)
                !r.seesAllCompanies && s.companies.isEmpty() -> getString(Res.string.user_add_err_company)
                r == Role.Salesman && !s.isSalesManager && s.chosenCompanies.any { it !in s.companyManagers } ->
                    getString(Res.string.user_add_err_manager, companyName(s.chosenCompanies.first { it !in s.companyManagers }))
                s.pin.length != PIN_LENGTH -> getString(Res.string.user_add_err_pin, PIN_LENGTH)
                else -> null
            }
            if (problem != null || r == null) {
                _state.update { it.copy(error = problem) }
                return@launch
            }
            _state.update { it.copy(busy = true, error = null) }
            val chosen = s.chosenCompanies
            val perCompany = r == Role.Salesman && !s.isSalesManager
            try {
                val added = repository.createUser(
                    NewUserRequest(
                        name = s.name.trim(),
                        phone = normalisePhoneInput(s.phone.trim()),
                        role = r.code,
                        companies = if (r.seesAllCompanies) companyCodes else chosen,
                        managerId = when {
                            r != Role.Salesman -> null
                            perCompany -> chosen.firstOrNull()?.let { s.companyManagers[it] }
                            else -> s.managerId
                        },
                        companyManagers = if (perCompany) s.companyManagers.filterKeys { it in chosen } else null,
                        pin = s.pin,
                    ),
                )
                app.message = getString(Res.string.user_add_added, added.name)
                _effects.tryEmit(AddUserEffect.Done)
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
