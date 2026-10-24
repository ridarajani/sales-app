package com.salesapp.ui.delivery

import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Camera
import com.salesapp.core.signaturePng
import com.salesapp.core.toBase64
import com.salesapp.data.AppState
import com.salesapp.data.DeliveryLineQty
import com.salesapp.data.DeliveryRequest
import com.salesapp.data.LoadingRepository
import com.salesapp.data.PlanDetail
import com.salesapp.data.PlanDrop
import com.salesapp.data.PlanDropLine
import com.salesapp.resources.Res
import com.salesapp.resources.delivery_done
import com.salesapp.resources.delivery_need_name
import com.salesapp.resources.delivery_need_reason
import com.salesapp.resources.delivery_need_signature
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

// One product on the drop, as the driver fills it in. Starts at everything that was loaded.
data class DeliveryLine(
    val line: PlanDropLine,
    val piecesPerCarton: Int,
    val cartons: Int?,
    val pieces: Int?,
    val reason: String = "",
) {
    val taken: Int get() = (cartons ?: 0) * piecesPerCarton.coerceAtLeast(1) + (pieces ?: 0)

    // Less than what went on the vehicle, so a reason is needed.
    val short: Boolean get() = taken < line.loaded

    val tooMuch: Boolean get() = taken > line.loaded
}

data class ConfirmDeliveryState(
    val loading: Boolean = true,
    val allowed: Boolean = true,
    val plan: PlanDetail? = null,
    val drop: PlanDrop? = null,
    val lines: List<DeliveryLine> = emptyList(),
    val receivedBy: String = "",
    val note: String = "",
    // The finished strokes, in the pad's own pixels, and how big the pad is.
    val strokes: List<List<Pair<Float, Float>>> = emptyList(),
    val padSize: IntSize = IntSize.Zero,
    val photo: ByteArray? = null,
    val cameraAvailable: Boolean = false,
    // Set by "Customer refused everything": every line goes to 0 and no signature is asked for.
    val refusedAll: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
) {
    // Nothing was taken at all, so the customer refused the whole drop.
    val nothingTaken: Boolean get() = lines.all { it.taken == 0 }

    val needsSignature: Boolean get() = !nothingTaken

    val hasSignature: Boolean get() = strokes.any { it.size >= 2 }

    // The driver cannot confirm a drop whose invoices are not made.
    val waitingForInvoices: Boolean get() = drop?.invoiced == false

    val nothingToDeliver: Boolean get() = drop != null && lines.isEmpty()

    // equals/hashCode are written out because of the photo bytes.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ConfirmDeliveryState) return false
        return loading == other.loading && allowed == other.allowed && plan == other.plan && drop == other.drop &&
            lines == other.lines && receivedBy == other.receivedBy && note == other.note && strokes == other.strokes &&
            padSize == other.padSize && photo.contentEquals(other.photo) && cameraAvailable == other.cameraAvailable &&
            refusedAll == other.refusedAll && busy == other.busy && error == other.error && actionError == other.actionError
    }

    override fun hashCode(): Int {
        var result = lines.hashCode()
        result = 31 * result + receivedBy.hashCode()
        result = 31 * result + strokes.hashCode()
        result = 31 * result + (photo?.contentHashCode() ?: 0)
        result = 31 * result + refusedAll.hashCode()
        result = 31 * result + busy.hashCode()
        return result
    }
}

sealed interface ConfirmDeliveryEvent {
    data object Retry : ConfirmDeliveryEvent
    data class EditCartons(val itemId: Int, val value: Int?) : ConfirmDeliveryEvent
    data class EditPieces(val itemId: Int, val value: Int?) : ConfirmDeliveryEvent
    data class EditReason(val itemId: Int, val value: String) : ConfirmDeliveryEvent
    data class ReceivedByChanged(val value: String) : ConfirmDeliveryEvent
    data class NoteChanged(val value: String) : ConfirmDeliveryEvent
    data class StrokeDone(val stroke: List<Pair<Float, Float>>) : ConfirmDeliveryEvent
    data class PadSized(val size: IntSize) : ConfirmDeliveryEvent
    data object ClearSignature : ConfirmDeliveryEvent
    data object TakePhoto : ConfirmDeliveryEvent
    data object RemovePhoto : ConfirmDeliveryEvent
    data object RefuseAll : ConfirmDeliveryEvent
    data object UndoRefuseAll : ConfirmDeliveryEvent
    data object Confirm : ConfirmDeliveryEvent
}

sealed interface ConfirmDeliveryEffect {
    // The drop is confirmed, so the screen closes.
    data object Done : ConfirmDeliveryEffect
}

class ConfirmDeliveryViewModel(
    private val planId: Int,
    private val customerId: Int,
    private val repository: LoadingRepository,
    private val camera: Camera,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        ConfirmDeliveryState(allowed = app.user?.role == "admin" || app.user?.role == "driver", cameraAvailable = camera.available),
    )
    val state: StateFlow<ConfirmDeliveryState> = _state.asStateFlow()

    private val _effects = Channel<ConfirmDeliveryEffect>(Channel.BUFFERED)
    val effects: Flow<ConfirmDeliveryEffect> = _effects.receiveAsFlow()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: ConfirmDeliveryEvent) {
        val s = _state.value
        if (!s.allowed || s.busy && event != ConfirmDeliveryEvent.Retry) return
        when (event) {
            ConfirmDeliveryEvent.Retry -> load()
            is ConfirmDeliveryEvent.EditCartons -> edit(event.itemId) { it.copy(cartons = event.value) }
            is ConfirmDeliveryEvent.EditPieces -> edit(event.itemId) { it.copy(pieces = event.value) }
            is ConfirmDeliveryEvent.EditReason -> edit(event.itemId) { it.copy(reason = event.value) }
            is ConfirmDeliveryEvent.ReceivedByChanged -> _state.update { it.copy(receivedBy = event.value, actionError = null) }
            is ConfirmDeliveryEvent.NoteChanged -> _state.update { it.copy(note = event.value) }
            is ConfirmDeliveryEvent.StrokeDone -> _state.update { it.copy(strokes = it.strokes + listOf(event.stroke), actionError = null) }
            is ConfirmDeliveryEvent.PadSized -> _state.update { it.copy(padSize = event.size) }
            ConfirmDeliveryEvent.ClearSignature -> _state.update { it.copy(strokes = emptyList()) }
            ConfirmDeliveryEvent.TakePhoto -> takePhoto()
            ConfirmDeliveryEvent.RemovePhoto -> _state.update { it.copy(photo = null) }
            // Refusing the whole drop empties every line and drops the signature.
            ConfirmDeliveryEvent.RefuseAll -> _state.update { current ->
                current.copy(
                    refusedAll = true,
                    lines = current.lines.map { it.copy(cartons = 0, pieces = 0) },
                    strokes = emptyList(),
                    actionError = null,
                )
            }
            ConfirmDeliveryEvent.UndoRefuseAll -> _state.update { current ->
                current.copy(
                    refusedAll = false,
                    lines = current.lines.map { it.copy(cartons = it.line.cartons, pieces = it.line.loosePieces, reason = "") },
                    actionError = null,
                )
            }
            ConfirmDeliveryEvent.Confirm -> confirm()
        }
    }

    private fun edit(itemId: Int, change: (DeliveryLine) -> DeliveryLine) {
        _state.update { s ->
            s.copy(
                lines = s.lines.map { if (it.line.itemId == itemId) change(it) else it },
                actionError = null,
            )
        }
    }

    private fun takePhoto() {
        camera.take(
            onDone = { bytes -> if (bytes != null) _state.update { it.copy(photo = bytes, actionError = null) } },
            onError = { message -> _state.update { it.copy(actionError = message) } },
        )
    }

    private fun confirm() {
        val s = _state.value
        if (s.busy || s.drop == null || s.lines.isEmpty() || s.waitingForInvoices) return
        viewModelScope.launch {
            // The same checks the server makes, so the driver is told before anything is sent.
            val short = s.lines.firstOrNull { it.short && it.reason.isBlank() }
            if (short != null) {
                _state.update { it.copy(actionError = getString(Res.string.delivery_need_reason)) }
                return@launch
            }
            val anything = !s.nothingTaken
            if (anything && s.receivedBy.isBlank()) {
                _state.update { it.copy(actionError = getString(Res.string.delivery_need_name)) }
                return@launch
            }
            val signature = if (anything) {
                val png = signaturePng(s.strokes, s.padSize.width, s.padSize.height)
                if (png == null) {
                    _state.update { it.copy(actionError = getString(Res.string.delivery_need_signature)) }
                    return@launch
                }
                png.toBase64()
            } else {
                null
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val request = DeliveryRequest(
                    customerId = customerId,
                    receivedBy = s.receivedBy.trim(),
                    signature = signature,
                    photo = s.photo?.toBase64(),
                    note = s.note.trim(),
                    lines = s.lines.map {
                        DeliveryLineQty(
                            itemId = it.line.itemId,
                            cartons = it.cartons ?: 0,
                            pieces = it.pieces ?: 0,
                            reason = if (it.short) it.reason.trim() else "",
                        )
                    },
                )
                repository.confirmDelivery(planId, request)
                app.message = getString(Res.string.delivery_done)
                _effects.trySend(ConfirmDeliveryEffect.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server's own words, which say exactly which product is wrong.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val plan = repository.plan(planId)
                val drop = plan.drops.firstOrNull { it.customerId == customerId }
                // Carton sizes come from the plan's products; a drop line only carries the split.
                val perCarton = plan.products.associate { it.productId to it.piecesPerCarton }
                val lines = drop?.lines.orEmpty()
                    .filter { it.loaded > 0 }
                    .map { line ->
                        DeliveryLine(
                            line = line,
                            piecesPerCarton = perCarton[line.productId] ?: 1,
                            cartons = line.cartons,
                            pieces = line.loosePieces,
                        )
                    }
                _state.update { it.copy(loading = false, plan = plan, drop = drop, lines = lines, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
