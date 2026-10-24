package com.salesapp.ui.returns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.randomUuid
import com.salesapp.data.AppState
import com.salesapp.data.InvoiceDetail
import com.salesapp.data.InvoiceLine
import com.salesapp.data.InvoiceRepository
import com.salesapp.data.RETURN_REASONS
import com.salesapp.data.ReturnDetail
import com.salesapp.data.ReturnLineQty
import com.salesapp.data.ReturnRequest
import com.salesapp.data.ReturnsRepository
import com.salesapp.data.canAdjustInvoices
import com.salesapp.resources.Res
import com.salesapp.resources.return_need_lines
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// One invoice line as it is being filled in: how much of it came back.
data class ReturnLineEntry(
    val line: InvoiceLine,
    val cartons: Int? = null,
    val pieces: Int? = null,
) {
    val perCarton: Int get() = line.piecesPerCarton.coerceAtLeast(1)

    val wanted: Int get() = (cartons ?: 0) * perCarton + (pieces ?: 0)

    // The most that can still come back: what was delivered less what already came back.
    val most: Int get() = line.canReturn ?: 0

    val tooMuch: Boolean get() = wanted > most
}

data class NewReturnState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val invoice: InvoiceDetail? = null,
    val lines: List<ReturnLineEntry> = emptyList(),
    val reason: String? = null,
    val note: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
    val saved: ReturnDetail? = null,
) {
    // Until a drop is confirmed the server cannot say what may come back.
    val notDeliveredYet: Boolean get() = invoice != null && invoice.lines.all { it.canReturn == null }

    val anything: Boolean get() = lines.any { it.wanted > 0 }

    val reasons: List<String> get() = RETURN_REASONS
}

sealed interface NewReturnEvent {
    data class EditCartons(val lineId: Int, val value: Int?) : NewReturnEvent
    data class EditPieces(val lineId: Int, val value: Int?) : NewReturnEvent
    data class ReasonChosen(val reason: String) : NewReturnEvent
    data class NoteChanged(val value: String) : NewReturnEvent
    data object Save : NewReturnEvent
    data object Retry : NewReturnEvent
}

class NewReturnViewModel(
    private val invoiceId: Int,
    private val invoices: InvoiceRepository,
    private val returns: ReturnsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(NewReturnState(allowed = app.user?.canAdjustInvoices() == true))
    val state: StateFlow<NewReturnState> = _state.asStateFlow()

    // One id per form, so a retry after a lost answer does not book the return twice.
    private var requestId = randomUuid()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: NewReturnEvent) {
        val s = _state.value
        if (!s.allowed || s.busy && event != NewReturnEvent.Retry) return
        when (event) {
            is NewReturnEvent.EditCartons -> edit(event.lineId) { it.copy(cartons = event.value) }
            is NewReturnEvent.EditPieces -> edit(event.lineId) { it.copy(pieces = event.value) }
            is NewReturnEvent.ReasonChosen -> _state.update { it.copy(reason = event.reason, actionError = null) }
            is NewReturnEvent.NoteChanged -> _state.update { it.copy(note = event.value) }
            NewReturnEvent.Save -> save()
            NewReturnEvent.Retry -> load()
        }
    }

    private fun edit(lineId: Int, change: (ReturnLineEntry) -> ReturnLineEntry) {
        _state.update { s ->
            s.copy(lines = s.lines.map { if (it.line.lineId == lineId) change(it) else it }, actionError = null)
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch {
            if (!s.anything) {
                _state.update { it.copy(actionError = getString(Res.string.return_need_lines)) }
                return@launch
            }
            val reason = s.reason ?: RETURN_REASONS.first()
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val saved = returns.addReturn(
                    invoiceId,
                    ReturnRequest(
                        lines = s.lines.filter { it.wanted > 0 }.map {
                            ReturnLineQty(invoiceLineId = it.line.lineId, cartons = it.cartons ?: 0, pieces = it.pieces ?: 0)
                        },
                        reason = reason,
                        note = s.note.trim(),
                        requestId = requestId,
                    ),
                )
                // Booked, so another return from this invoice is a new one.
                requestId = randomUuid()
                _state.update { it.copy(busy = false, saved = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server names the product and how much of it can still come back.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val invoice = invoices.invoice(invoiceId)
                _state.update {
                    it.copy(
                        loading = false,
                        invoice = invoice,
                        // A line with nothing left to come back is not shown at all.
                        lines = invoice.lines.filter { line -> (line.canReturn ?: 0) > 0 }.map { line -> ReturnLineEntry(line) },
                        reason = it.reason ?: RETURN_REASONS.first(),
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
