package com.salesapp.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.PendingAdjustment
import com.salesapp.data.StockRepository
import com.salesapp.data.canApproveStock
import com.salesapp.resources.Res
import com.salesapp.resources.approvals_approved
import com.salesapp.resources.approvals_rejected
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class ApprovalsState(
    val loading: Boolean = true,
    val rows: List<PendingAdjustment> = emptyList(),
    val error: String? = null,
    val allowed: Boolean = true,
    // The request being decided right now; its buttons are off.
    val decidingId: Int? = null,
    // The request the person is being asked to confirm, and whether it is an approval.
    val asking: PendingAdjustment? = null,
    val askingApprove: Boolean = true,
    // A problem from approving or rejecting, shown at the top.
    val actionError: String? = null,
) {
    // One group per company, in the order companies first appear; oldest request first inside each.
    val groups: List<Pair<String, List<PendingAdjustment>>>
        get() = rows.groupBy { it.companyCode }.map { (code, list) -> code to list.sortedBy { it.requestedAt } }
}

sealed interface ApprovalsEvent {
    data object Retry : ApprovalsEvent
    data object Refresh : ApprovalsEvent
    data class AskApprove(val row: PendingAdjustment) : ApprovalsEvent
    data class AskReject(val row: PendingAdjustment) : ApprovalsEvent
    data object Dismiss : ApprovalsEvent
    data object Confirm : ApprovalsEvent
}

class ApprovalsViewModel(
    private val repository: StockRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(ApprovalsState(allowed = app.user?.canApproveStock() == true))
    val state: StateFlow<ApprovalsState> = _state.asStateFlow()

    init {
        if (_state.value.allowed) load(showSpinner = true) else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: ApprovalsEvent) {
        if (!_state.value.allowed) return
        when (event) {
            ApprovalsEvent.Retry -> load(showSpinner = true)
            ApprovalsEvent.Refresh -> if (!_state.value.loading && _state.value.error == null && _state.value.decidingId == null) load(showSpinner = false)
            is ApprovalsEvent.AskApprove -> _state.update { it.copy(asking = event.row, askingApprove = true) }
            is ApprovalsEvent.AskReject -> _state.update { it.copy(asking = event.row, askingApprove = false) }
            ApprovalsEvent.Dismiss -> _state.update { it.copy(asking = null) }
            ApprovalsEvent.Confirm -> decide()
        }
    }

    private fun decide() {
        val s = _state.value
        val row = s.asking ?: return
        if (s.decidingId != null) return
        val approve = s.askingApprove
        _state.update { it.copy(asking = null, decidingId = row.id, actionError = null) }
        viewModelScope.launch {
            try {
                if (approve) repository.approve(row.id) else repository.reject(row.id)
                _state.update { it.copy(rows = it.rows.filter { r -> r.id != row.id }) }
                app.message = getString(if (approve) Res.string.approvals_approved else Res.string.approvals_rejected)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(actionError = message) }
            } finally {
                _state.update { it.copy(decidingId = null) }
            }
            load(showSpinner = false)
        }
    }

    private fun load(showSpinner: Boolean) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.pendingAdjustments()
                _state.update { it.copy(loading = false, rows = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
