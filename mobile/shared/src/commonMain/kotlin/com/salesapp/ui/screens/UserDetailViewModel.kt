package com.salesapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.UserRow
import com.salesapp.data.UsersRepository
import com.salesapp.data.canManageUser
import com.salesapp.resources.Res
import com.salesapp.resources.delete_done
import com.salesapp.resources.delete_kept
import com.salesapp.resources.user_detail_switched_off
import com.salesapp.resources.user_detail_switched_on
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

data class UserDetailState(
    val loading: Boolean = true,
    val user: UserRow? = null,
    val myId: Int? = null,
    val myRole: String? = null,
    val confirmOff: Boolean = false,
    // Changing or deleting this person: an admin may do it to anyone, a sales manager only to
    // their own salesmen, which is the same rule the server keeps.
    val canChange: Boolean = false,
    val asking: Boolean = false,
    val deleting: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
) {
    val isMe: Boolean get() = user != null && user.id == myId

    // Management only looks; a sales manager only looks after salesmen.
    val canManage: Boolean
        get() = user != null && (myRole == "admin" || (myRole == "sales_manager" && user.role == "salesman"))
}

sealed interface UserDetailEvent {
    data object Retry : UserDetailEvent
    data object Edit : UserDetailEvent
    data object ResetPin : UserDetailEvent
    data object AskSwitchOff : UserDetailEvent
    data object DismissSwitchOff : UserDetailEvent
    data object ConfirmSwitchOff : UserDetailEvent
    data object SwitchOn : UserDetailEvent
    data object AskDelete : UserDetailEvent
    data object DismissDelete : UserDetailEvent
    data object Delete : UserDetailEvent
}

sealed interface UserDetailEffect {
    data class ResetPin(val id: Int) : UserDetailEffect
    data class Edit(val id: Int) : UserDetailEffect
    // The list refreshes itself when it comes back, so going back is enough.
    data object Deleted : UserDetailEffect
}

class UserDetailViewModel(
    private val id: Int,
    private val repository: UsersRepository,
    private val app: AppState,
) : ViewModel() {
    private val me = app.session.user.value
    private val cached = repository.cached(id)

    private val _state = MutableStateFlow(
        UserDetailState(
            loading = cached == null,
            user = cached,
            myId = me?.id,
            myRole = me?.role,
            canChange = canChange(cached),
        ),
    )
    val state: StateFlow<UserDetailState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<UserDetailEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<UserDetailEffect> = _effects.asSharedFlow()

    init {
        if (cached == null) load()
    }

    fun onEvent(event: UserDetailEvent) {
        when (event) {
            UserDetailEvent.Retry -> load()
            UserDetailEvent.Edit -> if (_state.value.canChange) _effects.tryEmit(UserDetailEffect.Edit(id))
            UserDetailEvent.ResetPin -> _effects.tryEmit(UserDetailEffect.ResetPin(id))
            UserDetailEvent.AskSwitchOff -> _state.update { it.copy(confirmOff = true) }
            UserDetailEvent.DismissSwitchOff -> _state.update { it.copy(confirmOff = false) }
            UserDetailEvent.ConfirmSwitchOff -> {
                _state.update { it.copy(confirmOff = false) }
                change(on = false)
            }
            UserDetailEvent.SwitchOn -> change(on = true)
            UserDetailEvent.AskDelete -> if (_state.value.canChange) _state.update { it.copy(asking = true) }
            UserDetailEvent.DismissDelete -> _state.update { it.copy(asking = false) }
            UserDetailEvent.Delete -> delete()
        }
    }

    // A person nothing refers to goes; one with history keeps their name on old documents.
    private fun delete() {
        val s = _state.value
        val row = s.user ?: return
        if (s.deleting || !s.canChange) return
        _state.update { it.copy(deleting = true, asking = false, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.deleteUser(id)
                app.message = if (result.keptForHistory) {
                    getString(Res.string.delete_kept, row.name)
                } else {
                    getString(Res.string.delete_done, row.name)
                }
                _effects.tryEmit(UserDetailEffect.Deleted)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server's own words, such as a sales manager who still has salesmen.
                _state.update { it.copy(deleting = false, error = app.handleApiError(e)) }
            }
        }
    }

    private fun canChange(row: UserRow?): Boolean = row != null && me != null && me.canManageUser(row)

    // The server has no call for one person, so the repository reloads the list when it does not know them.
    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val row = repository.user(id)
                _state.update { it.copy(loading = false, user = row, canChange = canChange(row)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }

    private fun change(on: Boolean) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val updated = if (on) repository.switchOn(id) else repository.switchOff(id)
                _state.update { it.copy(user = updated, canChange = canChange(updated)) }
                app.message = getString(
                    if (on) Res.string.user_detail_switched_on else Res.string.user_detail_switched_off,
                    updated.name,
                )
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
