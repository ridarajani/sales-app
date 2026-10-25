package com.salesapp.ui.reports

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.shiftMonth
import com.salesapp.core.thisMonth
import com.salesapp.data.AppState
import com.salesapp.data.ReportsRepository
import com.salesapp.data.TargetRow
import com.salesapp.data.canSetTargets
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

// Each salesman's sales target for one month, in whole kwacha before VAT. Saved one at a time, so a
// phone that loses its network in the middle has not half-saved a list.
data class TargetsState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val error: String? = null,
    val month: String = "",
    val rows: List<TargetRow> = emptyList(),
    // The amount in each field, as typed. A missing key means the field is empty.
    val amounts: Map<Int, Long?> = emptyMap(),
    val saving: Set<Int> = emptySet(),
    // Whose target has just been saved, so the card can say so quietly.
    val saved: Set<Int> = emptySet(),
    val actionError: String? = null,
) {
    val none: Boolean get() = !loading && error == null && rows.isEmpty()
}

sealed interface TargetsEvent {
    data object Retry : TargetsEvent
    data object MonthBack : TargetsEvent
    data object MonthNext : TargetsEvent
    data class AmountChanged(val salesmanId: Int, val value: Long?) : TargetsEvent
    data class Save(val salesmanId: Int) : TargetsEvent
}

class TargetsViewModel(
    private val repository: ReportsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        TargetsState(allowed = app.user?.canSetTargets() == true, month = thisMonth()),
    )
    val state: StateFlow<TargetsState> = _state.asStateFlow()

    private var loadJob: Job? = null

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: TargetsEvent) {
        if (!_state.value.allowed) return
        when (event) {
            TargetsEvent.Retry -> load()
            TargetsEvent.MonthBack -> move(-1)
            TargetsEvent.MonthNext -> move(1)
            is TargetsEvent.AmountChanged -> _state.update {
                it.copy(
                    amounts = it.amounts + (event.salesmanId to event.value),
                    // Typing again means it is not saved any more.
                    saved = it.saved - event.salesmanId,
                    actionError = null,
                )
            }
            is TargetsEvent.Save -> save(event.salesmanId)
        }
    }

    private fun move(months: Int) {
        _state.update { it.copy(month = shiftMonth(it.month, months), actionError = null) }
        load()
    }

    private fun load() {
        loadJob?.cancel()
        val month = _state.value.month
        _state.update { it.copy(loading = true, error = null, saved = emptySet()) }
        loadJob = viewModelScope.launch {
            try {
                val rows = repository.targets(month)
                _state.update {
                    it.copy(
                        loading = false,
                        rows = rows,
                        amounts = rows.associate { row -> row.salesmanId to row.amount?.toLong() },
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }

    private fun save(salesmanId: Int) {
        val s = _state.value
        if (salesmanId in s.saving) return
        _state.update { it.copy(saving = it.saving + salesmanId, actionError = null) }
        viewModelScope.launch {
            try {
                // An empty field is sent as no amount at all, which takes the target away.
                val saved = repository.setTarget(salesmanId, s.month, s.amounts[salesmanId]?.toInt())
                _state.update {
                    it.copy(
                        saving = it.saving - salesmanId,
                        saved = it.saved + salesmanId,
                        rows = it.rows.map { row -> if (row.salesmanId == salesmanId) saved else row },
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(saving = it.saving - salesmanId, actionError = app.handleApiError(e)) }
            }
        }
    }
}
