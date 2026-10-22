package com.salesapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.UserRow
import com.salesapp.data.UsersRepository
import com.salesapp.resources.Res
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
    data object ResetPin : UserDetailEvent
    data object AskSwitchOff : UserDetailEvent
    data object DismissSwitchOff : UserDetailEvent
    data object ConfirmSwitchOff : UserDetailEvent
    data object SwitchOn : UserDetailEvent
}

sealed interface UserDetailEffect {
    data class ResetPin(val id: Int) : UserDetailEffect
}

class UserDetailViewModel(
    private val id: Int,
    private val repository: UsersRepository,
    private val app: AppState,
) : ViewModel() {
    private val me = app.session.user.value
    private val cached = repository.cached(id)

    private val _state = MutableStateFlow(
        UserDetailState(loading = cached == null, user = cached, myId = me?.id, myRole = me?.role),
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
            UserDetailEvent.ResetPin -> _effects.tryEmit(UserDetailEffect.ResetPin(id))
            UserDetailEvent.AskSwitchOff -> _state.update { it.copy(confirmOff = true) }
            UserDetailEvent.DismissSwitchOff -> _state.update { it.copy(confirmOff = false) }
            UserDetailEvent.ConfirmSwitchOff -> {
                _state.update { it.copy(confirmOff = false) }
                change(on = false)
            }
            UserDetailEvent.SwitchOn -> change(on = true)
        }
    }

    // The server has no call for one person, so the repository reloads the list when it does not know them.
    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val row = repository.user(id)
                _state.update { it.copy(loading = false, user = row) }
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
                _state.update { it.copy(user = updated) }
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
