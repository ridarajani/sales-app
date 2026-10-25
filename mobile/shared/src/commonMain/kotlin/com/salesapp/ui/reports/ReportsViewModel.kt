package com.salesapp.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.ReportGroup
import com.salesapp.data.ReportsRepository
import com.salesapp.data.canSetTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// The list of reports this person may open, as the server groups them.
data class ReportsState(
    val loading: Boolean = true,
    val error: String? = null,
    val groups: List<ReportGroup> = emptyList(),
    val query: String = "",
    val canSetTargets: Boolean = false,
) {
    // The groups with only the reports whose title matches what was typed.
    val shown: List<ReportGroup>
        get() {
            val q = query.trim()
            if (q.isEmpty()) return groups
            return groups.mapNotNull { group ->
                group.reports.filter { it.title.contains(q, ignoreCase = true) }
                    .takeIf { it.isNotEmpty() }
                    ?.let { group.copy(reports = it) }
            }
        }

    val nothingFound: Boolean get() = groups.isNotEmpty() && shown.isEmpty()

    val none: Boolean get() = !loading && error == null && groups.isEmpty()
}

sealed interface ReportsEvent {
    data object Retry : ReportsEvent
    data class QueryChanged(val value: String) : ReportsEvent
}

class ReportsViewModel(
    private val repository: ReportsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ReportsState(
            groups = repository.cachedCatalogue()?.groups.orEmpty(),
            canSetTargets = app.user?.canSetTargets() == true,
        ),
    )
    val state: StateFlow<ReportsState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: ReportsEvent) {
        when (event) {
            ReportsEvent.Retry -> load()
            is ReportsEvent.QueryChanged -> _state.update { it.copy(query = event.value) }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val catalogue = repository.catalogue()
                _state.update { it.copy(loading = false, groups = catalogue.groups, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
