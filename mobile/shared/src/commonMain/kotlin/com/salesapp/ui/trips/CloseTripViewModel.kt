package com.salesapp.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.CloseTripRequest
import com.salesapp.data.TripDetail
import com.salesapp.data.TripLine
import com.salesapp.data.TripLineQty
import com.salesapp.data.TripsRepository
import com.salesapp.resources.Res
import com.salesapp.resources.close_done
import com.salesapp.resources.close_need_cash
import com.salesapp.resources.trip_close_blocked
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

// One product as the leader counts what came back.
data class CloseLine(val line: TripLine, val cartons: Int? = null, val pieces: Int? = null) {
    val perCarton: Int get() = line.piecesPerCarton.coerceAtLeast(1)

    val back: Int get() = (cartons ?: 0) * perCarton + (pieces ?: 0)

    // What should come back is what is still on the van.
    val should: Int get() = line.onVehicle

    val missing: Int get() = (should - back).coerceAtLeast(0)

    val tooMuch: Boolean get() = back > should
}

data class CloseTripState(
    val loading: Boolean = true,
    val allowed: Boolean = true,
    val trip: TripDetail? = null,
    val lines: List<CloseLine> = emptyList(),
    val counted: Long? = null,
    val note: String = "",
    // Closing waits until everything the phone is holding has reached the server.
    val waitingToSend: Int = 0,
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
) {
    val cashSales: Double get() = trip?.cashSales ?: 0.0

    val float: Double get() = trip?.float ?: 0.0

    val cashExpenses: Double get() = trip?.cashExpenses ?: 0.0

    // Cash sales plus the float, less what was spent in cash.
    val expected: Long get() = (cashSales + float - cashExpenses).toLong()

    val difference: Long? get() = counted?.let { it - expected }

    val missingPieces: Int get() = lines.sumOf { it.missing }

    // The server asks for a note when the cash is out or something is missing.
    val needsNote: Boolean get() = (difference ?: 0L) != 0L || missingPieces > 0

    val blocked: Boolean get() = waitingToSend > 0
}

sealed interface CloseTripEvent {
    data class EditCartons(val productId: Int, val value: Int?) : CloseTripEvent
    data class EditPieces(val productId: Int, val value: Int?) : CloseTripEvent
    data class CountedChanged(val value: Long?) : CloseTripEvent
    data class NoteChanged(val value: String) : CloseTripEvent
    data object Close : CloseTripEvent
    data object Retry : CloseTripEvent
}

sealed interface CloseTripEffect {
    data object Closed : CloseTripEffect
}

class CloseTripViewModel(
    private val tripId: Int,
    private val repository: TripsRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(CloseTripState())
    val state: StateFlow<CloseTripState> = _state.asStateFlow()

    private val _effects = Channel<CloseTripEffect>(Channel.BUFFERED)
    val effects: Flow<CloseTripEffect> = _effects.receiveAsFlow()

    init {
        load()
    }

    fun onEvent(event: CloseTripEvent) {
        val s = _state.value
        if (s.busy && event != CloseTripEvent.Retry) return
        when (event) {
            is CloseTripEvent.EditCartons -> edit(event.productId) { it.copy(cartons = event.value) }
            is CloseTripEvent.EditPieces -> edit(event.productId) { it.copy(pieces = event.value) }
            is CloseTripEvent.CountedChanged -> _state.update { it.copy(counted = event.value, actionError = null) }
            is CloseTripEvent.NoteChanged -> _state.update { it.copy(note = event.value, actionError = null) }
            CloseTripEvent.Close -> close()
            CloseTripEvent.Retry -> load()
        }
    }

    private fun edit(productId: Int, change: (CloseLine) -> CloseLine) {
        _state.update { s ->
            s.copy(lines = s.lines.map { if (it.line.productId == productId) change(it) else it }, actionError = null)
        }
    }

    private fun close() {
        val s = _state.value
        viewModelScope.launch {
            // Nothing is closed while the phone is still holding sales: their stock and cash count.
            if (s.blocked) {
                _state.update { it.copy(actionError = getString(Res.string.trip_close_blocked)) }
                return@launch
            }
            val counted = s.counted
            if (counted == null) {
                _state.update { it.copy(actionError = getString(Res.string.close_need_cash)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val saved = repository.close(
                    tripId,
                    CloseTripRequest(
                        lines = s.lines.map { TripLineQty(it.line.productId, cartons = it.cartons ?: 0, pieces = it.pieces ?: 0) },
                        counted = counted.toInt(),
                        note = s.note.trim(),
                    ),
                )
                // Closed, so nothing needs keeping on the phone for it any more.
                repository.forget(tripId)
                app.message = getString(Res.string.close_done, saved.number)
                _effects.trySend(CloseTripEffect.Closed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server names what is short or missing and asks for a note.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val trip = repository.trip(tripId)
                val waiting = repository.outboxOf(tripId).count
                _state.update {
                    it.copy(
                        loading = false,
                        allowed = trip.canClose,
                        trip = trip,
                        waitingToSend = waiting,
                        // The count starts empty: what came back has to be counted, not confirmed.
                        lines = trip.lines.map { line -> CloseLine(line) },
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
