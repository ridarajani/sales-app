package com.salesapp.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.AdjustRequest
import com.salesapp.data.StockBatch
import com.salesapp.data.StockProduct
import com.salesapp.data.StockRepository
import com.salesapp.data.canKeepStock
import com.salesapp.resources.Res
import com.salesapp.resources.adjust_changed
import com.salesapp.resources.adjust_err_direction
import com.salesapp.resources.adjust_err_note
import com.salesapp.resources.adjust_err_reason
import com.salesapp.resources.number_err_quantity
import com.salesapp.ui.catalog.FormEffect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class AdjustStockState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val loadError: String? = null,
    val product: StockProduct? = null,
    val batch: StockBatch? = null,
    // "damaged", "expired", "lost", "found" or "other".
    val reason: String? = null,
    // "add" or "remove"; only used for "other".
    val direction: String? = null,
    val note: String = "",
    val cartons: Int? = null,
    val pieces: Int? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface AdjustStockEvent {
    data class Reason(val value: String) : AdjustStockEvent
    data class Direction(val value: String) : AdjustStockEvent
    data class Note(val value: String) : AdjustStockEvent
    data class Cartons(val value: Int?) : AdjustStockEvent
    data class Pieces(val value: Int?) : AdjustStockEvent
    data object Save : AdjustStockEvent
    data object Retry : AdjustStockEvent
}

class AdjustStockViewModel(
    private val batchId: Int,
    private val repository: StockRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(AdjustStockState(allowed = app.user?.canKeepStock() == true))
    val state: StateFlow<AdjustStockState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FormEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<FormEffect> = _effects.asSharedFlow()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: AdjustStockEvent) {
        when (event) {
            is AdjustStockEvent.Reason -> _state.update { it.copy(reason = event.value, error = null) }
            is AdjustStockEvent.Direction -> _state.update { it.copy(direction = event.value, error = null) }
            is AdjustStockEvent.Note -> _state.update { it.copy(note = event.value, error = null) }
            is AdjustStockEvent.Cartons -> _state.update { it.copy(cartons = event.value, error = null) }
            is AdjustStockEvent.Pieces -> _state.update { it.copy(pieces = event.value, error = null) }
            AdjustStockEvent.Save -> save()
            AdjustStockEvent.Retry -> load()
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val found = repository.batch(batchId)
                _state.update {
                    it.copy(loading = false, product = found?.first, batch = found?.second)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = app.handleApiError(e)) }
            }
        }
    }

    private fun save() {
        val s = _state.value
        if (s.busy) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val problem = when {
                s.reason == null -> getString(Res.string.adjust_err_reason)
                s.reason == "other" && s.direction == null -> getString(Res.string.adjust_err_direction)
                s.reason == "other" && s.note.isBlank() -> getString(Res.string.adjust_err_note)
                (s.cartons ?: 0) * (s.product?.piecesPerCarton ?: 1) + (s.pieces ?: 0) <= 0 -> getString(Res.string.number_err_quantity)
                else -> null
            }
            if (problem != null || s.reason == null) {
                _state.update { it.copy(busy = false, error = problem) }
                return@launch
            }
            try {
                val result = repository.adjust(
                    AdjustRequest(
                        batchId = batchId,
                        reason = s.reason,
                        direction = if (s.reason == "other") s.direction else null,
                        cartons = s.cartons ?: 0,
                        pieces = s.pieces ?: 0,
                        note = s.note.trim(),
                    ),
                )
                app.message = getString(Res.string.adjust_changed, result.cartons.toString(), result.loosePieces.toString())
                _effects.tryEmit(FormEffect.Done)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = app.handleApiError(e)) }
            } finally {
                _state.update { it.copy(busy = false) }
            }
        }
    }
}
