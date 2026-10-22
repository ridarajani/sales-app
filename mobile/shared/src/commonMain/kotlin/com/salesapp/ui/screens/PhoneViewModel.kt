package com.salesapp.ui.screens

import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.AppConfig
import com.salesapp.core.isValidPhone
import com.salesapp.core.normalisePhoneInput
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class PhoneState(
    val phone: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val ready: Boolean = false,
    val apiUrl: String = "",
    val isCustomServer: Boolean = false,
)

sealed interface PhoneEvent {
    data class PhoneChanged(val value: String) : PhoneEvent
    data object Next : PhoneEvent
    data object OpenServerAddress : PhoneEvent
}

sealed interface PhoneEffect {
    data class GoToPin(val phone: String, val name: String) : PhoneEffect
    data object OpenServerAddress : PhoneEffect
}

class PhoneViewModel(private val app: AppState) : ViewModel() {
    private val _state = MutableStateFlow(
        PhoneState(
            phone = app.lastPhone,
            error = app.loginNotice,
            ready = isReady(app.lastPhone),
            apiUrl = app.apiUrl,
            isCustomServer = app.apiUrl != AppConfig.DEFAULT_API_URL,
        ),
    )
    val state: StateFlow<PhoneState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<PhoneEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<PhoneEffect> = _effects.asSharedFlow()

    init {
        // The server address can change on its own screen while this one waits behind it.
        viewModelScope.launch {
            snapshotFlow { app.apiUrl }.collect { url ->
                _state.update { it.copy(apiUrl = url, isCustomServer = url != AppConfig.DEFAULT_API_URL) }
            }
        }
    }

    fun onEvent(event: PhoneEvent) {
        when (event) {
            is PhoneEvent.PhoneChanged -> {
                val phone = event.value.filter { c -> c.isDigit() || c == '+' || c == ' ' || c == '-' }
                _state.update { it.copy(phone = phone, ready = isReady(phone), error = null) }
            }
            PhoneEvent.Next -> next()
            PhoneEvent.OpenServerAddress -> if (!_state.value.busy) _effects.tryEmit(PhoneEffect.OpenServerAddress)
        }
    }

    private fun next() {
        val current = _state.value
        if (current.busy || !current.ready) return
        val clean = normalisePhoneInput(current.phone.trim())
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val found = app.api.checkPhone(clean)
                app.lastPhone = clean
                app.loginNotice = null
                _effects.tryEmit(PhoneEffect.GoToPin(clean, found.name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                _state.update { it.copy(error = e.message) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }

    private fun isReady(phone: String) = isValidPhone(normalisePhoneInput(phone))
}
