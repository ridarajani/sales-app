package com.salesapp.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.SearchGroup
import com.salesapp.data.SearchSource
import com.salesapp.ui.navigation.searchRouteFor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// How long after the last letter the server is asked, so typing a shop name is one call, not eight.
const val SEARCH_WAIT_MILLIS = 300L

// At least this many letters before anything is asked at all.
const val SEARCH_MIN_LETTERS = 2

data class SearchState(
    val query: String = "",
    val searching: Boolean = false,
    val groups: List<SearchGroup> = emptyList(),
    val error: String? = null,
    // True once an answer for the words on screen has come back.
    val answered: Boolean = false,
) {
    val tooShort: Boolean get() = query.trim().length < SEARCH_MIN_LETTERS

    val nothing: Boolean get() = !tooShort && answered && groups.isEmpty()
}

sealed interface SearchEvent {
    data class QueryChanged(val value: String) : SearchEvent
    data class OpenItem(val groupKey: String, val id: Int) : SearchEvent
}

sealed interface SearchEffect {
    data class Open(val route: Any) : SearchEffect
}

class SearchViewModel(
    private val source: SearchSource,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    // Only the newest search is kept: cancelling the one before it drops its answer, however late
    // it arrives, so the list on screen always belongs to the words in the box.
    private var searchJob: Job? = null

    private val _effects = MutableSharedFlow<SearchEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<SearchEffect> = _effects.asSharedFlow()

    fun onEvent(event: SearchEvent) {
        when (event) {
            is SearchEvent.QueryChanged -> type(event.value)
            // Each kind of thing opens the screen this person already has for it; a few open
            // nothing at all, and then the card simply does nothing.
            is SearchEvent.OpenItem -> searchRouteFor(event.groupKey, event.id, app.user)?.let {
                _effects.tryEmit(SearchEffect.Open(it))
            }
        }
    }

    private fun type(value: String) {
        searchJob?.cancel()
        val q = value.trim()
        if (q.length < SEARCH_MIN_LETTERS) {
            // Too few letters to be worth a call: the screen says what to type.
            _state.update { it.copy(query = value, searching = false, groups = emptyList(), error = null, answered = false) }
            return
        }
        _state.update { it.copy(query = value, error = null) }
        searchJob = viewModelScope.launch {
            delay(SEARCH_WAIT_MILLIS)
            _state.update { it.copy(searching = true) }
            try {
                val result = source.search(q)
                _state.update { it.copy(searching = false, groups = result.groups, answered = true, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(searching = false, error = app.handleApiError(e)) }
            }
        }
    }
}
