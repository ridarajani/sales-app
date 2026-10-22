package com.salesapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.UsersRepository
import com.salesapp.resources.Res
import com.salesapp.resources.reset_pin_changed
import com.salesapp.resources.reset_pin_mismatch
import com.salesapp.ui.components.PIN_LENGTH
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class ResetPinState(
    // Null when the person is not known (for example after the app restarted).
    val userName: String? = null,
    val pin: String = "",
    val confirming: Boolean = false,
    val busy: Boolean = false,
    val stepping: Boolean = false,
    val error: String? = null,
)

sealed interface ResetPinEvent {
    data class PinChanged(val value: String) : ResetPinEvent
}

sealed interface ResetPinEffect {
    data object Done : ResetPinEffect
}

class ResetPinViewModel(
    private val id: Int,
    private val repository: UsersRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(ResetPinState(userName = repository.cached(id)?.name))
    val state: StateFlow<ResetPinState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ResetPinEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<ResetPinEffect> = _effects.asSharedFlow()

    private var first = ""

    fun onEvent(event: ResetPinEvent) {
        when (event) {
            is ResetPinEvent.PinChanged -> onPin(event.value)
        }
    }

    private fun onPin(value: String) {
        val s = _state.value
        if (s.busy || s.stepping) return
        _state.update { it.copy(pin = value) }
        if (value.length < PIN_LENGTH) return
        if (!s.confirming) {
            // Let the fourth dot show before moving on.
            _state.update { it.copy(stepping = true) }
            viewModelScope.launch {
                delay(150)
                first = value
                _state.update { it.copy(pin = "", confirming = true, error = null, stepping = false) }
            }
        } else if (value == first) {
            save(value)
        } else {
            _state.update { it.copy(stepping = true) }
            viewModelScope.launch {
                delay(150)
                val message = getString(Res.string.reset_pin_mismatch)
                first = ""
                _state.update { it.copy(pin = "", confirming = false, error = message, stepping = false) }
            }
        }
    }

    private fun save(newPin: String) {
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                repository.resetPin(id, newPin)
                app.message = getString(Res.string.reset_pin_changed)
                _effects.tryEmit(ResetPinEffect.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = app.handleApiError(e), pin = "") }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
