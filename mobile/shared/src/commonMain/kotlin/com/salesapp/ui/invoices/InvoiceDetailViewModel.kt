package com.salesapp.ui.invoices

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Documents
import com.salesapp.data.AppState
import com.salesapp.data.InvoiceDetail
import com.salesapp.data.InvoiceRepository
import com.salesapp.data.canCollect
import com.salesapp.data.canSeeInvoices
import com.salesapp.resources.Res
import com.salesapp.resources.invoice_no_viewer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class InvoiceDetailState(
    val loading: Boolean = true,
    val allowed: Boolean = true,
    // Phase 7: whoever may record a payment gets a "Collect money" button.
    val canCollect: Boolean = false,
    val invoice: InvoiceDetail? = null,
    val error: String? = null,
    // The PDF is being fetched; both buttons are off.
    val busy: Boolean = false,
    val actionError: String? = null,
)

sealed interface InvoiceDetailEvent {
    data object Retry : InvoiceDetailEvent
    // A payment may have changed the balance while this screen was away.
    data object Refresh : InvoiceDetailEvent
    // Opens the PDF in the phone's viewer, which is where Print and Save live.
    data object Print : InvoiceDetailEvent
    // Opens the phone's share sheet, where WhatsApp is.
    data object Share : InvoiceDetailEvent
}

class InvoiceDetailViewModel(
    private val id: Int,
    private val repository: InvoiceRepository,
    private val documents: Documents,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        InvoiceDetailState(allowed = app.user?.canSeeInvoices() == true, canCollect = app.user?.canCollect() == true),
    )
    val state: StateFlow<InvoiceDetailState> = _state.asStateFlow()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: InvoiceDetailEvent) {
        if (!_state.value.allowed) return
        when (event) {
            InvoiceDetailEvent.Retry -> load()
            InvoiceDetailEvent.Refresh -> if (_state.value.invoice != null && !_state.value.busy) load(showSpinner = false)
            InvoiceDetailEvent.Print -> sendPdf(share = false)
            InvoiceDetailEvent.Share -> sendPdf(share = true)
        }
    }

    // Downloads the PDF with the auth header, then hands it to the phone.
    private fun sendPdf(share: Boolean) {
        val invoice = _state.value.invoice ?: return
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val file = repository.pdf(invoice.id)
                val opened = documents.open("${invoice.number}.pdf", file.bytes, file.contentType, share)
                _state.update { it.copy(busy = false, actionError = if (opened) null else getString(Res.string.invoice_no_viewer)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load(showSpinner: Boolean = true) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val invoice = repository.invoice(id)
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
