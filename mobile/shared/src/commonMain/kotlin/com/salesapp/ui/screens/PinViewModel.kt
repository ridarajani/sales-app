package com.salesapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import com.salesapp.ui.components.PIN_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PinState(
    val name: String = "",
    val pin: String = "",
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface PinEvent {
    data class PinChanged(val value: String) : PinEvent
}

class PinViewModel(
    private val phone: String,
    name: String,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(PinState(name = name))
    val state: StateFlow<PinState> = _state.asStateFlow()

    fun onEvent(event: PinEvent) {
        when (event) {
            is PinEvent.PinChanged -> {
                _state.update { it.copy(pin = event.value) }
                if (event.value.length == PIN_LENGTH) tryLogin(event.value)
            }
        }
    }

    private fun tryLogin(pin: String) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val reply = app.api.login(phone, pin)
                app.loginNotice = null
                // Signing in changes the app state, and the app then opens Home.
                app.signedIn(reply.token, reply.user)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.message, pin = "") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
