package com.salesapp.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.AppConfig
import com.salesapp.data.AppState
import com.salesapp.resources.Res
import com.salesapp.resources.server_must_start_http
import com.salesapp.resources.server_saved
import com.salesapp.resources.server_using_default
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class ServerAddressState(
    val url: String = "",
    val error: String? = null,
)

sealed interface ServerAddressEvent {
    data class UrlChanged(val value: String) : ServerAddressEvent
    data object Save : ServerAddressEvent
    data object UseDefault : ServerAddressEvent
}

sealed interface ServerAddressEffect {
    data object Done : ServerAddressEffect
}

class ServerAddressViewModel(private val app: AppState) : ViewModel() {
    private val _state = MutableStateFlow(ServerAddressState(url = app.apiUrl))
    val state: StateFlow<ServerAddressState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ServerAddressEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<ServerAddressEffect> = _effects.asSharedFlow()

    fun onEvent(event: ServerAddressEvent) {
        when (event) {
            is ServerAddressEvent.UrlChanged -> _state.update { it.copy(url = event.value) }
            ServerAddressEvent.Save -> save()
            ServerAddressEvent.UseDefault -> useDefault()
        }
    }

    private fun save() {
        val clean = _state.value.url.trim().trimEnd('/')
        viewModelScope.launch {
            if (!clean.startsWith("http://") && !clean.startsWith("https://")) {
                _state.update { it.copy(error = getString(Res.string.server_must_start_http)) }
            } else {
                app.changeApiUrl(clean)
                app.message = getString(Res.string.server_saved)
                _effects.emit(ServerAddressEffect.Done)
            }
        }
    }

    private fun useDefault() {
        viewModelScope.launch {
            app.changeApiUrl(null)
            _state.update { it.copy(url = AppConfig.DEFAULT_API_URL) }
            app.message = getString(Res.string.server_using_default)
            _effects.emit(ServerAddressEffect.Done)
        }
    }
}
