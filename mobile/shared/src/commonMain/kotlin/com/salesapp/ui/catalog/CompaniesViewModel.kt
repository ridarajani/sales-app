package com.salesapp.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.Company
import com.salesapp.data.ListsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CompaniesState(
    val loading: Boolean = true,
    val companies: List<Company> = emptyList(),
    val error: String? = null,
)

sealed interface CompaniesEvent {
    data object Retry : CompaniesEvent
    data class Open(val code: String) : CompaniesEvent
}

sealed interface CompaniesEffect {
    data class Open(val code: String) : CompaniesEffect
}

class CompaniesViewModel(
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val mine = companiesOf(app.session.user.value)
    private val _state = MutableStateFlow(CompaniesState())
    val state: StateFlow<CompaniesState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<CompaniesEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<CompaniesEffect> = _effects.asSharedFlow()

    init {
        load()
    }

    fun onEvent(event: CompaniesEvent) {
        when (event) {
            CompaniesEvent.Retry -> load()
            is CompaniesEvent.Open -> _effects.tryEmit(CompaniesEffect.Open(event.code))
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.companies().filter { it.code in mine }
                _state.update { it.copy(loading = false, companies = rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
