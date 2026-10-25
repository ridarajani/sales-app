package com.salesapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.Role
import com.salesapp.data.UserRow
import com.salesapp.data.UserUpdateRequest
import com.salesapp.data.UsersRepository
import com.salesapp.data.canChangeUserRole
import com.salesapp.data.canManageUser
import com.salesapp.data.companyCodes
import com.salesapp.resources.Res
import com.salesapp.resources.user_add_err_company
import com.salesapp.resources.user_add_err_job
import com.salesapp.resources.user_add_err_manager
import com.salesapp.resources.user_add_err_name
import com.salesapp.resources.user_add_err_phone
import com.salesapp.resources.user_edit_no_change
import com.salesapp.resources.user_edit_saved
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

data class EditUserState(
    val loading: Boolean = true,
    val loadError: String? = null,
    val allowed: Boolean = true,
    val users: List<UserRow> = emptyList(),
    val name: String = "",
    val phone: String = "",
    val role: Role? = null,
    val companies: Set<String> = emptySet(),
    // A sales manager hands out their own companies only, like the Add person form.
    val myCompanies: List<String> = emptyList(),
    val isSalesManager: Boolean = false,
    // A salesman's sales manager for each company.
    val companyManagers: Map<String, Int> = emptyMap(),
    // A sales manager cannot change what job their salesman does.
    val canChangeRole: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    fun managersFor(code: String): List<UserRow> =
        users.filter { it.role == "sales_manager" && it.active && code in it.companies }

    val pickableCompanies: List<String>
        get() = if (isSalesManager) companyCodes.filter { it in myCompanies } else companyCodes

    val chosenCompanies: List<String> get() = pickableCompanies.filter { it in companies }
}

sealed interface EditUserEvent {
    data class Name(val value: String) : EditUserEvent
    data class Phone(val value: String) : EditUserEvent
    data class RoleChosen(val role: Role) : EditUserEvent
    data class ManagerChosen(val code: String, val id: Int) : EditUserEvent
    data class CompanyToggled(val code: String) : EditUserEvent
    data object Save : EditUserEvent
    data object Retry : EditUserEvent
}

sealed interface EditUserEffect {
    data object Done : EditUserEffect
}

class EditUserViewModel(
    private val id: Int,
    private val repository: UsersRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(EditUserState())
    val state: StateFlow<EditUserState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<EditUserEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<EditUserEffect> = _effects.asSharedFlow()

    private var original: UserRow? = null

    init {
        load()
    }

    fun onEvent(event: EditUserEvent) {
        val s = _state.value
        if (!s.allowed || s.busy && event != EditUserEvent.Retry) return
        when (event) {
            is EditUserEvent.Name -> _state.update { it.copy(name = event.value, error = null) }
            is EditUserEvent.Phone -> _state.update { it.copy(phone = event.value, error = null) }
            is EditUserEvent.RoleChosen -> _state.update {
                if (!it.canChangeRole) {
                    it
                } else {
                    // Another job means other companies and no sales manager to keep.
                    it.copy(role = event.role, companyManagers = emptyMap(), error = null)
                }
            }
            is EditUserEvent.ManagerChosen -> _state.update {
                it.copy(companyManagers = it.companyManagers + (event.code to event.id), error = null)
            }
            is EditUserEvent.CompanyToggled -> _state.update {
                val companies = if (event.code in it.companies) it.companies - event.code else it.companies + event.code
                it.copy(companies = companies, companyManagers = it.companyManagers.filterKeys { c -> c in companies }, error = null)
            }
            EditUserEvent.Save -> save()
            EditUserEvent.Retry -> load()
        }
    }

    private fun save() {
        val s = _state.value
        val old = original ?: return
        if (s.busy) return
        viewModelScope.launch {
            val role = s.role
            val problem = when {
                s.name.isBlank() -> getString(Res.string.user_add_err_name)
                s.phone.isBlank() -> getString(Res.string.user_add_err_phone)
                role == null -> getString(Res.string.user_add_err_job)
                role.seesAllCompanies -> null
                s.companies.isEmpty() -> getString(Res.string.user_add_err_company)
                role == Role.Salesman && !s.isSalesManager && s.chosenCompanies.any { s.companyManagers[it] == null } ->
                    getString(Res.string.user_add_err_manager)
                else -> null
            }
            if (problem != null) {
                _state.update { it.copy(error = problem) }
                return@launch
            }
            // Only what actually changed is sent, so nothing else is touched by accident.
            // Companies this user cannot see stay as they are.
            val hidden = old.companies.filter { it !in s.pickableCompanies }
            val companies = if (role!!.seesAllCompanies) null else s.chosenCompanies + hidden
            val request = UserUpdateRequest(
                name = s.name.trim().takeIf { it != old.name },
                phone = s.phone.trim().takeIf { it != old.phone },
                role = role.code.takeIf { it != old.role },
                companies = companies?.takeIf { it.toSet() != old.companies.toSet() },
                // A sales manager is the manager, so the server fills that in itself.
                companyManagers = s.companyManagers.takeIf { role == Role.Salesman && !s.isSalesManager && it != managersOf(old) },
            )
            if (request == UserUpdateRequest()) {
                _state.update { it.copy(error = getString(Res.string.user_edit_no_change)) }
                return@launch
            }
            _state.update { it.copy(busy = true, error = null) }
            try {
                val saved = repository.updateUser(id, request)
                app.message = getString(Res.string.user_edit_saved, saved.name)
                _effects.tryEmit(EditUserEffect.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server's own words, such as a phone already used by someone else.
                _state.update { it.copy(busy = false, error = app.handleApiError(e)) }
            }
        }
    }

    private fun managersOf(user: UserRow): Map<String, Int> =
        user.managers.associate { it.company to it.managerId }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val users = repository.refresh()
                val user = users.firstOrNull { it.id == id }
                val me = app.user
                if (user == null || me == null) {
                    _state.update { it.copy(loading = false, allowed = false) }
                    return@launch
                }
                original = user
                _state.update {
                    it.copy(
                        loading = false,
                        allowed = me.canManageUser(user),
                        users = users,
                        name = user.name,
                        phone = user.phone,
                        role = Role.fromCode(user.role),
                        companies = user.companies.toSet(),
                        myCompanies = me.companies,
                        isSalesManager = me.role == "sales_manager",
                        companyManagers = managersOf(user),
                        canChangeRole = me.canChangeUserRole(),
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = app.handleApiError(e)) }
            }
        }
    }
}
