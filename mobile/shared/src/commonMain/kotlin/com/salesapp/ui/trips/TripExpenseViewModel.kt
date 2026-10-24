package com.salesapp.ui.trips

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Camera
import com.salesapp.core.randomUuid
import com.salesapp.core.toBase64
import com.salesapp.data.AppState
import com.salesapp.data.ExpenseOutcome
import com.salesapp.data.TripExpenseRequest
import com.salesapp.data.TripsRepository
import com.salesapp.resources.Res
import com.salesapp.resources.trip_expense_need_amount
import com.salesapp.resources.trip_expense_need_kind
import com.salesapp.resources.trip_expense_saved
import com.salesapp.resources.trip_saved_offline
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

data class TripExpenseState(
    val loading: Boolean = true,
    val allowed: Boolean = true,
    val kinds: List<String> = emptyList(),
    val kind: String? = null,
    val note: String = "",
    val amount: Long? = null,
    val photo: ByteArray? = null,
    val cameraAvailable: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
) {
    // "Fuel", or "Other · two new tyres" when a note is typed as well.
    val description: String
        get() = listOfNotNull(kind, note.trim().takeIf { it.isNotBlank() }).joinToString(" · ")

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is TripExpenseState) return false
        return loading == other.loading && allowed == other.allowed && kinds == other.kinds && kind == other.kind &&
            note == other.note && amount == other.amount && photo.contentEquals(other.photo) &&
            cameraAvailable == other.cameraAvailable && busy == other.busy && error == other.error &&
            actionError == other.actionError
    }

    override fun hashCode(): Int {
        var result = kind?.hashCode() ?: 0
        result = 31 * result + note.hashCode()
        result = 31 * result + (amount?.hashCode() ?: 0)
        result = 31 * result + (photo?.contentHashCode() ?: 0)
        result = 31 * result + busy.hashCode()
        return result
    }
}

sealed interface TripExpenseEvent {
    data class KindChosen(val kind: String) : TripExpenseEvent
    data class NoteChanged(val value: String) : TripExpenseEvent
    data class AmountChanged(val value: Long?) : TripExpenseEvent
    data object TakePhoto : TripExpenseEvent
    data object RemovePhoto : TripExpenseEvent
    data object Save : TripExpenseEvent
    data object Retry : TripExpenseEvent
}

sealed interface TripExpenseEffect {
    data object Saved : TripExpenseEffect
}

class TripExpenseViewModel(
    private val tripId: Int,
    private val repository: TripsRepository,
    private val camera: Camera,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(TripExpenseState(cameraAvailable = camera.available))
    val state: StateFlow<TripExpenseState> = _state.asStateFlow()

    private val _effects = Channel<TripExpenseEffect>(Channel.BUFFERED)
    val effects: Flow<TripExpenseEffect> = _effects.receiveAsFlow()

    // One id per form, kept through every retry.
    private var requestId = randomUuid()

    init {
        load()
    }

    fun onEvent(event: TripExpenseEvent) {
        val s = _state.value
        if (s.busy && event != TripExpenseEvent.Retry) return
        when (event) {
            is TripExpenseEvent.KindChosen -> _state.update { it.copy(kind = event.kind, actionError = null) }
            is TripExpenseEvent.NoteChanged -> _state.update { it.copy(note = event.value, actionError = null) }
            is TripExpenseEvent.AmountChanged -> _state.update { it.copy(amount = event.value, actionError = null) }
            TripExpenseEvent.TakePhoto -> camera.take(
                onDone = { bytes -> if (bytes != null) _state.update { it.copy(photo = bytes, actionError = null) } },
                onError = { message -> _state.update { it.copy(actionError = message) } },
            )
            TripExpenseEvent.RemovePhoto -> _state.update { it.copy(photo = null) }
            TripExpenseEvent.Save -> save()
            TripExpenseEvent.Retry -> load()
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch {
            if (s.kind.isNullOrBlank()) {
                _state.update { it.copy(actionError = getString(Res.string.trip_expense_need_kind)) }
                return@launch
            }
            if ((s.amount ?: 0) <= 0) {
                _state.update { it.copy(actionError = getString(Res.string.trip_expense_need_amount)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val outcome = repository.spend(
                    tripId,
                    TripExpenseRequest(
                        requestId = requestId,
                        amount = (s.amount ?: 0).toInt(),
                        description = s.description,
                        photo = s.photo?.toBase64(),
                    ),
                )
                app.message = when (outcome) {
                    is ExpenseOutcome.Sent -> getString(Res.string.trip_expense_saved)
                    is ExpenseOutcome.Saved -> getString(Res.string.trip_saved_offline)
                }
                requestId = randomUuid()
                _effects.trySend(TripExpenseEffect.Saved)
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
                // The kinds come with the pack, so they are there with no network too.
                val pack = repository.savedPack(tripId) ?: repository.pack(tripId)
                _state.update {
                    it.copy(loading = false, allowed = pack.trip.canSell, kinds = pack.expenseKinds, kind = it.kind ?: pack.expenseKinds.firstOrNull())
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
