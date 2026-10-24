package com.salesapp.ui.returns

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.randomUuid
import com.salesapp.data.AppState
import com.salesapp.data.InvoiceDetail
import com.salesapp.data.InvoiceNoteDetail
import com.salesapp.data.InvoiceRepository
import com.salesapp.data.NOTE_REASONS
import com.salesapp.data.NoteRequest
import com.salesapp.data.ReturnsRepository
import com.salesapp.data.canAdjustInvoices
import com.salesapp.data.noteNeedsTheirNumber
import com.salesapp.resources.Res
import com.salesapp.resources.note_need_amount
import com.salesapp.resources.note_need_their_number
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class NewNoteState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val invoice: InvoiceDetail? = null,
    val kind: String = "credit_issued",
    val amount: Long? = null,
    val reason: String = NOTE_REASONS.first(),
    val theirNumber: String = "",
    val note: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
    val saved: InvoiceNoteDetail? = null,
) {
    // Only a note the customer sent us carries their own number.
    val needsTheirNumber: Boolean get() = noteNeedsTheirNumber(kind)

    val reasons: List<String> get() = NOTE_REASONS
}

sealed interface NewNoteEvent {
    data class KindChosen(val kind: String) : NewNoteEvent
    data class AmountChanged(val value: Long?) : NewNoteEvent
    data class ReasonChosen(val reason: String) : NewNoteEvent
    data class TheirNumberChanged(val value: String) : NewNoteEvent
    data class NoteChanged(val value: String) : NewNoteEvent
    data object Save : NewNoteEvent
    data object Retry : NewNoteEvent
}

class NewNoteViewModel(
    private val invoiceId: Int,
    private val invoices: InvoiceRepository,
    private val returns: ReturnsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(NewNoteState(allowed = app.user?.canAdjustInvoices() == true))
    val state: StateFlow<NewNoteState> = _state.asStateFlow()

    // One id per form, so a retry does not make a second note.
    private var requestId = randomUuid()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: NewNoteEvent) {
        val s = _state.value
        if (!s.allowed || s.busy && event != NewNoteEvent.Retry) return
        when (event) {
            is NewNoteEvent.KindChosen -> _state.update {
                // Their number only belongs to a note they sent us.
                it.copy(kind = event.kind, theirNumber = if (noteNeedsTheirNumber(event.kind)) it.theirNumber else "", actionError = null)
            }
            is NewNoteEvent.AmountChanged -> _state.update { it.copy(amount = event.value, actionError = null) }
            is NewNoteEvent.ReasonChosen -> _state.update { it.copy(reason = event.reason, actionError = null) }
            is NewNoteEvent.TheirNumberChanged -> _state.update { it.copy(theirNumber = event.value, actionError = null) }
            is NewNoteEvent.NoteChanged -> _state.update { it.copy(note = event.value) }
            NewNoteEvent.Save -> save()
            NewNoteEvent.Retry -> load()
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch {
            if ((s.amount ?: 0) <= 0) {
                _state.update { it.copy(actionError = getString(Res.string.note_need_amount)) }
                return@launch
            }
            if (s.needsTheirNumber && s.theirNumber.isBlank()) {
                _state.update { it.copy(actionError = getString(Res.string.note_need_their_number)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val saved = returns.addNote(
                    invoiceId,
                    NoteRequest(
                        kind = s.kind,
                        amount = (s.amount ?: 0).toInt(),
                        reason = s.reason,
                        theirNumber = s.theirNumber.trim(),
                        note = s.note.trim(),
                        requestId = requestId,
                    ),
                )
                requestId = randomUuid()
                _state.update { it.copy(busy = false, saved = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
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
                _state.update { it.copy(loading = false, invoice = invoice, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
