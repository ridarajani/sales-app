package com.salesapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.UserRow
import com.salesapp.data.UsersRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class UsersState(
    val loading: Boolean = true,
    val users: List<UserRow> = emptyList(),
    val error: String? = null,
    val isSalesManager: Boolean = false,
    val canAdd: Boolean = false,
)

sealed interface UsersEvent {
    data object Retry : UsersEvent
    data object Refresh : UsersEvent
    data class OpenUser(val id: Int) : UsersEvent
    data object AddUser : UsersEvent
}

sealed interface UsersEffect {
    data class OpenUser(val id: Int) : UsersEffect
    data object AddUser : UsersEffect
}

class UsersViewModel(
    private val repository: UsersRepository,
    private val app: AppState,
) : ViewModel() {
    private val me = app.session.user.value
    private val isSalesManager = me?.role == "sales_manager"

    private val _state = MutableStateFlow(
        UsersState(
            isSalesManager = isSalesManager,
            canAdd = me?.role == "admin" || isSalesManager,
        ),
    )
    val state: StateFlow<UsersState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<UsersEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<UsersEffect> = _effects.asSharedFlow()

    init {
        load(showSpinner = true)
    }

    fun onEvent(event: UsersEvent) {
        when (event) {
            UsersEvent.Retry -> load(showSpinner = true)
            UsersEvent.Refresh -> if (!_state.value.loading && _state.value.error == null) load(showSpinner = false)
            is UsersEvent.OpenUser -> _effects.tryEmit(UsersEffect.OpenUser(event.id))
            UsersEvent.AddUser -> _effects.tryEmit(UsersEffect.AddUser)
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.refresh()
                val shown = if (isSalesManager) rows.filter { it.id != me?.id } else rows
                _state.update { it.copy(loading = false, users = shown, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
