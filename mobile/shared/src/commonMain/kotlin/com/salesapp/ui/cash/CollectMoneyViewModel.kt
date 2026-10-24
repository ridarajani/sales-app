package com.salesapp.ui.cash

import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Camera
import com.salesapp.core.signaturePng
import com.salesapp.core.toBase64
import com.salesapp.data.AppState
import com.salesapp.data.CashRepository
import com.salesapp.data.CollectCompany
import com.salesapp.data.CollectInfo
import com.salesapp.data.CollectRequest
import com.salesapp.data.Customer
import com.salesapp.data.OpenInvoice
import com.salesapp.data.PaymentDetail
import com.salesapp.data.User
import com.salesapp.data.canCollect
import com.salesapp.data.isOfficeCollector
import com.salesapp.resources.Res
import com.salesapp.resources.collect_need_amount
import com.salesapp.resources.collect_need_name
import com.salesapp.resources.collect_need_proof
import com.salesapp.resources.collect_need_reference
import com.salesapp.resources.collect_need_signature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class CollectState(
    val allowed: Boolean = true,
    val user: User? = null,
    // Step 1: search and pick the shop.
    val query: String = "",
    val customers: List<Customer> = emptyList(),
    val searching: Boolean = false,
    // Step 2 onwards, once a shop is picked.
    val loading: Boolean = false,
    val info: CollectInfo? = null,
    val companyCode: String? = null,
    val invoiceId: Int? = null,
    val mode: String = "cash",
    val amount: Long? = null,
    // Behind the "Discount or tax off" toggle.
    val showLess: Boolean = false,
    val discount: Long? = null,
    val wht: Long? = null,
    val whtCertificate: String = "",
    val reference: String = "",
    val note: String = "",
    val receivedBy: String = "",
    val strokes: List<List<Pair<Float, Float>>> = emptyList(),
    val padSize: IntSize = IntSize.Zero,
    val proof: ByteArray? = null,
    val cameraAvailable: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
    // Filled in once the payment is saved.
    val saved: PaymentDetail? = null,
) {
    val company: CollectCompany? get() = info?.companies?.firstOrNull { it.code == companyCode }

    val invoices: List<OpenInvoice> get() = company?.invoices.orEmpty()

    val invoice: OpenInvoice? get() = invoices.firstOrNull { it.id == invoiceId }

    // The customer signs for every payment except a transfer recorded at the office.
    val needsSignature: Boolean get() = mode == "cash" || info?.signatureNeeded == true

    val hasSignature: Boolean get() = strokes.any { it.size >= 2 }

    // Bank and Airtel need proof of the payment; Airtel needs its reference too.
    val needsProof: Boolean get() = mode != "cash" && (amount ?: 0) > 0

    val needsReference: Boolean get() = mode == "airtel" && (amount ?: 0) > 0

    // A discount or tax can only come off an invoice.
    val canUseLess: Boolean get() = invoiceId != null

    val total: Long get() = (amount ?: 0) + (discount ?: 0) + (wht ?: 0)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CollectState) return false
        return allowed == other.allowed && user == other.user && query == other.query && customers == other.customers &&
            searching == other.searching && loading == other.loading && info == other.info && companyCode == other.companyCode &&
            invoiceId == other.invoiceId && mode == other.mode && amount == other.amount && showLess == other.showLess &&
            discount == other.discount && wht == other.wht && whtCertificate == other.whtCertificate && reference == other.reference &&
            note == other.note && receivedBy == other.receivedBy && strokes == other.strokes && padSize == other.padSize &&
            proof.contentEquals(other.proof) && cameraAvailable == other.cameraAvailable && busy == other.busy &&
            error == other.error && actionError == other.actionError && saved == other.saved
    }

    override fun hashCode(): Int {
        var result = query.hashCode()
        result = 31 * result + (info?.hashCode() ?: 0)
        result = 31 * result + (amount?.hashCode() ?: 0)
        result = 31 * result + strokes.hashCode()
        result = 31 * result + (proof?.contentHashCode() ?: 0)
        result = 31 * result + busy.hashCode()
        result = 31 * result + (saved?.hashCode() ?: 0)
        return result
    }
}

sealed interface CollectEvent {
    data class QueryChanged(val value: String) : CollectEvent
    data class CustomerChosen(val id: Int) : CollectEvent
    data object ChangeCustomer : CollectEvent
    data class CompanyChosen(val code: String) : CollectEvent
    data class InvoiceChosen(val id: Int?) : CollectEvent
    data class ModeChosen(val mode: String) : CollectEvent
    data class AmountChanged(val value: Long?) : CollectEvent
    data object ToggleLess : CollectEvent
    data class DiscountChanged(val value: Long?) : CollectEvent
    data class WhtChanged(val value: Long?) : CollectEvent
    data class CertificateChanged(val value: String) : CollectEvent
    data class ReferenceChanged(val value: String) : CollectEvent
    data class NoteChanged(val value: String) : CollectEvent
    data class ReceivedByChanged(val value: String) : CollectEvent
    data class StrokeDone(val stroke: List<Pair<Float, Float>>) : CollectEvent
    data class PadSized(val size: IntSize) : CollectEvent
    data object ClearSignature : CollectEvent
    data object TakeProof : CollectEvent
    data object RemoveProof : CollectEvent
    data object Save : CollectEvent
    data object Retry : CollectEvent
    // After saving: start again with a clean screen.
    data object Another : CollectEvent
}

class CollectMoneyViewModel(
    // All three may be set when the screen is opened from an invoice.
    customerId: Int?,
    companyCode: String?,
    invoiceId: Int?,
    private val repository: CashRepository,
    private val camera: Camera,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        CollectState(
            allowed = app.user?.canCollect() == true,
            user = app.user,
            cameraAvailable = camera.available,
            companyCode = companyCode,
            invoiceId = invoiceId,
        ),
    )
    val state: StateFlow<CollectState> = _state.asStateFlow()

    private var searchJob: Job? = null

    init {
        if (!_state.value.allowed) {
            _state.update { it.copy(loading = false) }
        } else if (customerId != null) {
            // Opened from an invoice: the shop, company and invoice are already picked.
            loadCustomer(customerId, keepCompany = companyCode, keepInvoice = invoiceId)
        } else {
            search()
        }
    }

    fun onEvent(event: CollectEvent) {
        val s = _state.value
        if (!s.allowed || s.busy) return
        when (event) {
            is CollectEvent.QueryChanged -> {
                if (event.value == s.query) return
                _state.update { it.copy(query = event.value) }
                search(debounce = true)
            }
            is CollectEvent.CustomerChosen -> loadCustomer(event.id)
            CollectEvent.ChangeCustomer -> {
                _state.update {
                    CollectState(allowed = true, user = it.user, cameraAvailable = it.cameraAvailable, query = it.query, customers = it.customers)
                }
                search()
            }
            is CollectEvent.CompanyChosen -> _state.update {
                // Another company means another set of invoices.
                it.copy(companyCode = event.code, invoiceId = null, discount = null, wht = null, showLess = false, actionError = null)
            }
            is CollectEvent.InvoiceChosen -> _state.update {
                val keepLess = event.id != null
                it.copy(
                    invoiceId = event.id,
                    discount = if (keepLess) it.discount else null,
                    wht = if (keepLess) it.wht else null,
                    whtCertificate = if (keepLess) it.whtCertificate else "",
                    showLess = it.showLess && keepLess,
                    actionError = null,
                )
            }
            is CollectEvent.ModeChosen -> _state.update {
                // The proof belongs to the way it was paid.
                it.copy(mode = event.mode, proof = if (event.mode == "cash") null else it.proof, reference = if (event.mode == "airtel") it.reference else "", actionError = null)
            }
            is CollectEvent.AmountChanged -> _state.update { it.copy(amount = event.value, actionError = null) }
            CollectEvent.ToggleLess -> _state.update {
                if (!it.canUseLess) {
                    it
                } else {
                    it.copy(showLess = !it.showLess, discount = null, wht = null, whtCertificate = "", actionError = null)
                }
            }
            is CollectEvent.DiscountChanged -> _state.update { it.copy(discount = event.value, actionError = null) }
            is CollectEvent.WhtChanged -> _state.update { it.copy(wht = event.value, actionError = null) }
            is CollectEvent.CertificateChanged -> _state.update { it.copy(whtCertificate = event.value) }
            is CollectEvent.ReferenceChanged -> _state.update { it.copy(reference = event.value, actionError = null) }
            is CollectEvent.NoteChanged -> _state.update { it.copy(note = event.value) }
            is CollectEvent.ReceivedByChanged -> _state.update { it.copy(receivedBy = event.value, actionError = null) }
            is CollectEvent.StrokeDone -> _state.update { it.copy(strokes = it.strokes + listOf(event.stroke), actionError = null) }
            is CollectEvent.PadSized -> _state.update { it.copy(padSize = event.size) }
            CollectEvent.ClearSignature -> _state.update { it.copy(strokes = emptyList()) }
            CollectEvent.TakeProof -> camera.take(
                onDone = { bytes -> if (bytes != null) _state.update { it.copy(proof = bytes, actionError = null) } },
                onError = { message -> _state.update { it.copy(actionError = message) } },
            )
            CollectEvent.RemoveProof -> _state.update { it.copy(proof = null) }
            CollectEvent.Save -> save()
            CollectEvent.Retry -> _state.value.info?.customer?.id?.let { loadCustomer(it) } ?: search()
            CollectEvent.Another -> {
                val customerId = s.info?.customer?.id
                _state.update { CollectState(allowed = true, user = it.user, cameraAvailable = it.cameraAvailable) }
                if (customerId != null) loadCustomer(customerId) else search()
            }
        }
    }

    private fun search(debounce: Boolean = false) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (debounce) delay(300)
            _state.update { it.copy(searching = true, error = null) }
            try {
                val rows = repository.customers(_state.value.query)
                _state.update { it.copy(searching = false, customers = rows) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(searching = false, error = message) }
            }
        }
    }

    private fun loadCustomer(customerId: Int, keepCompany: String? = null, keepInvoice: Int? = null) {
        _state.update { it.copy(loading = true, error = null, saved = null) }
        viewModelScope.launch {
            try {
                val info = repository.collectInfo(customerId)
                _state.update { s ->
                    // One company: pick it straight away, there is nothing to choose.
                    val code = keepCompany ?: s.companyCode?.takeIf { c -> info.companies.any { it.code == c } }
                        ?: info.companies.singleOrNull()?.code
                    s.copy(
                        loading = false,
                        info = info,
                        companyCode = code,
                        invoiceId = keepInvoice ?: s.invoiceId,
                        // The office only needs a signature for cash.
                        mode = s.mode,
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

    private fun save() {
        val s = _state.value
        val company = s.companyCode ?: return
        val customer = s.info?.customer ?: return
        viewModelScope.launch {
            // The same checks the server makes, so the person is told before anything is sent.
            if (s.total <= 0) {
                _state.update { it.copy(actionError = getString(Res.string.collect_need_amount)) }
                return@launch
            }
            if (s.needsReference && s.reference.isBlank()) {
                _state.update { it.copy(actionError = getString(Res.string.collect_need_reference)) }
                return@launch
            }
            if (s.needsProof && s.proof == null) {
                _state.update { it.copy(actionError = getString(Res.string.collect_need_proof)) }
                return@launch
            }
            if (s.needsSignature && s.receivedBy.isBlank()) {
                _state.update { it.copy(actionError = getString(Res.string.collect_need_name)) }
                return@launch
            }
            val signature = if (s.needsSignature) {
                val png = signaturePng(s.strokes, s.padSize.width, s.padSize.height)
                if (png == null) {
                    _state.update { it.copy(actionError = getString(Res.string.collect_need_signature)) }
                    return@launch
                }
                png.toBase64()
            } else {
                null
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val saved = repository.collect(
                    CollectRequest(
                        customerId = customer.id,
                        companyCode = company,
                        invoiceId = s.invoiceId,
                        mode = s.mode,
                        amount = (s.amount ?: 0).toInt(),
                        discount = (s.discount ?: 0).toInt(),
                        wht = (s.wht ?: 0).toInt(),
                        whtCertificate = s.whtCertificate.trim(),
                        reference = s.reference.trim(),
                        note = s.note.trim(),
                        receivedBy = s.receivedBy.trim(),
                        signature = signature,
                        proof = s.proof?.toBase64(),
                    ),
                )
                _state.update { it.copy(busy = false, saved = saved) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server's own words, which name the invoice or the amount that is wrong.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }
}
