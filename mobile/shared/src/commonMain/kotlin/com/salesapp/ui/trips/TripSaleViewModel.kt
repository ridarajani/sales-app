package com.salesapp.ui.trips

import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Camera
import com.salesapp.core.nowUtcTimestamp
import com.salesapp.core.randomUuid
import com.salesapp.core.signaturePng
import com.salesapp.core.toBase64
import com.salesapp.data.ApiException
import com.salesapp.data.AppState
import com.salesapp.data.PackCustomer
import com.salesapp.data.SaleOutcome
import com.salesapp.data.TripDetail
import com.salesapp.data.TripLine
import com.salesapp.data.TripSaleDetail
import com.salesapp.data.TripSaleLineQty
import com.salesapp.data.TripSaleRequest
import com.salesapp.data.TripsRepository
import com.salesapp.data.keepForLater
import com.salesapp.resources.Res
import com.salesapp.resources.sale_need_customer
import com.salesapp.resources.sale_need_lines
import com.salesapp.resources.sale_need_name
import com.salesapp.resources.sale_need_price
import com.salesapp.resources.sale_need_reference
import com.salesapp.resources.sale_need_signature
import com.salesapp.resources.trip_saved_offline
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// One product on the sale: how much, and the price per carton the leader typed.
data class SaleLine(
    val line: TripLine,
    val onVehicle: Int,
    val cartons: Int? = null,
    val pieces: Int? = null,
    val price: Long? = null,
) {
    val perCarton: Int get() = line.piecesPerCarton.coerceAtLeast(1)

    val wanted: Int get() = (cartons ?: 0) * perCarton + (pieces ?: 0)

    // Never more than is left on the van.
    val tooMuch: Boolean get() = wanted > onVehicle

    val amount: Long get() = if (price == null) 0 else (wanted.toLong() * price) / perCarton
}

data class TripSaleState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val trip: TripDetail? = null,
    val customers: List<PackCustomer> = emptyList(),
    val query: String = "",
    val customerId: Int? = null,
    val stop: String = "",
    val invoiceType: String = "vat",
    val payment: String = "cash",
    val reference: String = "",
    val receivedBy: String = "",
    val strokes: List<List<Pair<Float, Float>>> = emptyList(),
    val padSize: IntSize = IntSize.Zero,
    val proof: ByteArray? = null,
    val cameraAvailable: Boolean = false,
    val lines: List<SaleLine> = emptyList(),
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
    // Filled in once it reaches the server.
    val saved: TripSaleDetail? = null,
    // True when it went into the outbox instead.
    val savedOffline: Boolean = false,
) {
    val customer: PackCustomer? get() = customers.firstOrNull { it.id == customerId }

    val shown: List<PackCustomer>
        get() = if (query.isBlank()) customers else customers.filter { it.name.contains(query.trim(), ignoreCase = true) }

    // A cash-only shop cannot buy on account.
    val canUseCredit: Boolean get() = customer?.cashOnly != true

    val needsReference: Boolean get() = payment == "airtel"

    val hasSignature: Boolean get() = strokes.any { it.size >= 2 }

    val anything: Boolean get() = lines.any { it.wanted > 0 }

    val total: Long get() = lines.sumOf { it.amount }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TripSaleState) return false
        return allowed == other.allowed && loading == other.loading && trip == other.trip && customers == other.customers &&
            query == other.query && customerId == other.customerId && stop == other.stop && invoiceType == other.invoiceType &&
            payment == other.payment && reference == other.reference && receivedBy == other.receivedBy &&
            strokes == other.strokes && padSize == other.padSize && proof.contentEquals(other.proof) &&
            cameraAvailable == other.cameraAvailable && lines == other.lines && busy == other.busy &&
            error == other.error && actionError == other.actionError && saved == other.saved && savedOffline == other.savedOffline
    }

    override fun hashCode(): Int {
        var result = customerId ?: 0
        result = 31 * result + lines.hashCode()
        result = 31 * result + strokes.hashCode()
        result = 31 * result + (proof?.contentHashCode() ?: 0)
        result = 31 * result + busy.hashCode()
        result = 31 * result + (saved?.hashCode() ?: 0)
        return result
    }
}

sealed interface TripSaleEvent {
    data class QueryChanged(val value: String) : TripSaleEvent
    data class CustomerChosen(val id: Int) : TripSaleEvent
    data object ChangeCustomer : TripSaleEvent
    data class StopChosen(val stop: String) : TripSaleEvent
    data class InvoiceTypeChosen(val type: String) : TripSaleEvent
    data class PaymentChosen(val payment: String) : TripSaleEvent
    data class ReferenceChanged(val value: String) : TripSaleEvent
    data class ReceivedByChanged(val value: String) : TripSaleEvent
    data class EditCartons(val productId: Int, val value: Int?) : TripSaleEvent
    data class EditPieces(val productId: Int, val value: Int?) : TripSaleEvent
    data class EditPrice(val productId: Int, val value: Long?) : TripSaleEvent
    data class StrokeDone(val stroke: List<Pair<Float, Float>>) : TripSaleEvent
    data class PadSized(val size: IntSize) : TripSaleEvent
    data object ClearSignature : TripSaleEvent
    data object TakeProof : TripSaleEvent
    data object RemoveProof : TripSaleEvent
    data object Save : TripSaleEvent
    data object Another : TripSaleEvent
    data object Retry : TripSaleEvent
}

class TripSaleViewModel(
    private val tripId: Int,
    private val repository: TripsRepository,
    private val camera: Camera,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(TripSaleState(cameraAvailable = camera.available))
    val state: StateFlow<TripSaleState> = _state.asStateFlow()

    // Made when the form opens and kept through every retry, so one sale is never saved twice.
    private var requestId = randomUuid()

    init {
        load()
    }

    fun onEvent(event: TripSaleEvent) {
        val s = _state.value
        if (s.busy && event != TripSaleEvent.Retry) return
        when (event) {
            is TripSaleEvent.QueryChanged -> _state.update { it.copy(query = event.value) }
            is TripSaleEvent.CustomerChosen -> pickCustomer(event.id)
            TripSaleEvent.ChangeCustomer -> _state.update { it.copy(customerId = null, query = "") }
            is TripSaleEvent.StopChosen -> _state.update { it.copy(stop = event.stop, actionError = null) }
            is TripSaleEvent.InvoiceTypeChosen -> _state.update { it.copy(invoiceType = event.type, actionError = null) }
            is TripSaleEvent.PaymentChosen -> _state.update {
                // A cash-only shop cannot buy on account, so that choice is simply not offered.
                if (event.payment == "credit" && !it.canUseCredit) it else it.copy(payment = event.payment, actionError = null)
            }
            is TripSaleEvent.ReferenceChanged -> _state.update { it.copy(reference = event.value, actionError = null) }
            is TripSaleEvent.ReceivedByChanged -> _state.update { it.copy(receivedBy = event.value, actionError = null) }
            is TripSaleEvent.EditCartons -> edit(event.productId) { it.copy(cartons = event.value) }
            is TripSaleEvent.EditPieces -> edit(event.productId) { it.copy(pieces = event.value) }
            is TripSaleEvent.EditPrice -> edit(event.productId) { it.copy(price = event.value) }
            is TripSaleEvent.StrokeDone -> _state.update { it.copy(strokes = it.strokes + listOf(event.stroke), actionError = null) }
            is TripSaleEvent.PadSized -> _state.update { it.copy(padSize = event.size) }
            TripSaleEvent.ClearSignature -> _state.update { it.copy(strokes = emptyList()) }
            TripSaleEvent.TakeProof -> camera.take(
                onDone = { bytes -> if (bytes != null) _state.update { it.copy(proof = bytes, actionError = null) } },
                onError = { message -> _state.update { it.copy(actionError = message) } },
            )
            TripSaleEvent.RemoveProof -> _state.update { it.copy(proof = null) }
            TripSaleEvent.Save -> save()
            TripSaleEvent.Another -> {
                // A new sale is a new id; the pack and what is on the van are read again.
                requestId = randomUuid()
                _state.update { TripSaleState(cameraAvailable = it.cameraAvailable, customers = it.customers, trip = it.trip, stop = it.stop) }
                load()
            }
            TripSaleEvent.Retry -> load()
        }
    }

    private fun edit(productId: Int, change: (SaleLine) -> SaleLine) {
        _state.update { s ->
            s.copy(lines = s.lines.map { if (it.line.productId == productId) change(it) else it }, actionError = null)
        }
    }

    // Picking a customer brings their usual invoice type and their last prices.
    private fun pickCustomer(id: Int) {
        _state.update { s ->
            val customer = s.customers.firstOrNull { it.id == id } ?: return@update s
            s.copy(
                customerId = id,
                invoiceType = customer.lastInvoiceType ?: s.invoiceType,
                payment = if (customer.cashOnly && s.payment == "credit") "cash" else s.payment,
                lines = s.lines.map { line -> line.copy(price = line.price ?: customer.lastPriceOf(line.line.productId)) },
                actionError = null,
            )
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch {
            val customer = s.customerId
            if (customer == null) {
                _state.update { it.copy(actionError = getString(Res.string.sale_need_customer)) }
                return@launch
            }
            if (!s.anything) {
                _state.update { it.copy(actionError = getString(Res.string.sale_need_lines)) }
                return@launch
            }
            if (s.lines.any { it.wanted > 0 && (it.price ?: 0) <= 0 }) {
                _state.update { it.copy(actionError = getString(Res.string.sale_need_price)) }
                return@launch
            }
            if (s.needsReference && s.reference.isBlank()) {
                _state.update { it.copy(actionError = getString(Res.string.sale_need_reference)) }
                return@launch
            }
            if (s.receivedBy.isBlank()) {
                _state.update { it.copy(actionError = getString(Res.string.sale_need_name)) }
                return@launch
            }
            // The customer signs for every sale on a trip.
            val signature = signaturePng(s.strokes, s.padSize.width, s.padSize.height)
            if (signature == null) {
                _state.update { it.copy(actionError = getString(Res.string.sale_need_signature)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val request = TripSaleRequest(
                    requestId = requestId,
                    customerId = customer,
                    stop = s.stop,
                    invoiceType = s.invoiceType,
                    payment = s.payment,
                    reference = s.reference.trim(),
                    receivedBy = s.receivedBy.trim(),
                    signature = signature.toBase64(),
                    proof = s.proof?.toBase64(),
                    // The phone's own time, so a sale made with no signal keeps the moment it happened.
                    soldAt = nowUtcTimestamp(),
                    lines = s.lines.filter { it.wanted > 0 }.map {
                        TripSaleLineQty(
                            productId = it.line.productId,
                            cartons = it.cartons ?: 0,
                            pieces = it.pieces ?: 0,
                            pricePerCarton = (it.price ?: 0).toInt(),
                        )
                    },
                )
                when (val outcome = repository.sell(tripId, request)) {
                    is SaleOutcome.Sent -> _state.update { it.copy(busy = false, saved = outcome.sale) }
                    is SaleOutcome.Saved -> {
                        app.message = getString(Res.string.trip_saved_offline)
                        _state.update { it.copy(busy = false, savedOffline = true) }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server's own words: not on the van, cash-only, and so on.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                // A fresh pack first, so the van count is right; the phone's own copy is the fall
                // back only when there is no network to ask.
                val pack = try {
                    repository.pack(tripId)
                } catch (e: ApiException) {
                    if (e.keepForLater) repository.savedPack(tripId) ?: throw e else throw e
                }
                val outbox = repository.outboxOf(tripId)
                val perCarton = pack.trip.lines.associate { it.productId to it.piecesPerCarton }
                val waiting = outbox.soldPieces(perCarton)
                _state.update { s ->
                    s.copy(
                        loading = false,
                        allowed = pack.trip.canSell,
                        trip = pack.trip,
                        customers = pack.customers,
                        stop = s.stop.ifBlank { pack.trip.stops.firstOrNull().orEmpty() },
                        lines = pack.trip.lines.map { line ->
                            // What this phone has already sold but not sent comes off at once.
                            val left = (line.onVehicle - (waiting[line.productId] ?: 0)).coerceAtLeast(0)
                            SaleLine(line, onVehicle = left, price = s.customer?.lastPriceOf(line.productId))
                        },
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
