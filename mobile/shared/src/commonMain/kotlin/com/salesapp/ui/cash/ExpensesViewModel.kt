package com.salesapp.ui.cash

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.core.Camera
import com.salesapp.core.randomUuid
import com.salesapp.core.toBase64
import com.salesapp.data.AppState
import com.salesapp.data.CashRepository
import com.salesapp.data.Company
import com.salesapp.data.Expense
import com.salesapp.data.ExpenseRequest
import com.salesapp.data.canRecordExpenses
import com.salesapp.resources.Res
import com.salesapp.resources.expense_need_amount
import com.salesapp.resources.expense_need_company
import com.salesapp.resources.expense_need_what
import com.salesapp.resources.expense_saved
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

data class ExpensesState(
    val allowed: Boolean = true,
    val canAdd: Boolean = false,
    val loading: Boolean = true,
    val expenses: List<Expense> = emptyList(),
    val error: String? = null,
)

sealed interface ExpensesEvent {
    data object Retry : ExpensesEvent
    data object Refresh : ExpensesEvent
}

class ExpensesViewModel(
    private val repository: CashRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        // Everyone who can see expenses can open the list; only the three roles can add one.
        ExpensesState(allowed = app.user != null, canAdd = app.user?.canRecordExpenses() == true),
    )
    val state: StateFlow<ExpensesState> = _state.asStateFlow()

    private var loadedOnce = false

    init {
        load()
    }

    fun onEvent(event: ExpensesEvent) {
        when (event) {
            ExpensesEvent.Retry -> load()
            ExpensesEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
        }
    }

    private fun load(showSpinner: Boolean = true) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.expenses()
                loadedOnce = true
                _state.update { it.copy(loading = false, expenses = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}

data class NewExpenseState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    val companies: List<Company> = emptyList(),
    val companyCode: String? = null,
    val amount: Long? = null,
    val description: String = "",
    val mode: String = "cash",
    val photo: ByteArray? = null,
    val cameraAvailable: Boolean = false,
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is NewExpenseState) return false
        return allowed == other.allowed && loading == other.loading && companies == other.companies &&
            companyCode == other.companyCode && amount == other.amount && description == other.description &&
            mode == other.mode && photo.contentEquals(other.photo) && cameraAvailable == other.cameraAvailable &&
            busy == other.busy && error == other.error && actionError == other.actionError
    }

    override fun hashCode(): Int {
        var result = companyCode?.hashCode() ?: 0
        result = 31 * result + (amount?.hashCode() ?: 0)
        result = 31 * result + description.hashCode()
        result = 31 * result + mode.hashCode()
        result = 31 * result + (photo?.contentHashCode() ?: 0)
        result = 31 * result + busy.hashCode()
        return result
    }
}

sealed interface NewExpenseEvent {
    data class CompanyChosen(val code: String) : NewExpenseEvent
    data class AmountChanged(val value: Long?) : NewExpenseEvent
    data class DescriptionChanged(val value: String) : NewExpenseEvent
    data class ModeChosen(val mode: String) : NewExpenseEvent
    data object TakePhoto : NewExpenseEvent
    data object RemovePhoto : NewExpenseEvent
    data object Save : NewExpenseEvent
    data object Retry : NewExpenseEvent
}

sealed interface NewExpenseEffect {
    data object Saved : NewExpenseEffect
}

class NewExpenseViewModel(
    private val repository: CashRepository,
    private val camera: Camera,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        NewExpenseState(allowed = app.user?.canRecordExpenses() == true, cameraAvailable = camera.available),
    )
    val state: StateFlow<NewExpenseState> = _state.asStateFlow()

    private val _effects = Channel<NewExpenseEffect>(Channel.BUFFERED)
    val effects: Flow<NewExpenseEffect> = _effects.receiveAsFlow()

    // One id per form, so a retry after a lost answer does not record the expense twice.
    private var requestId = randomUuid()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: NewExpenseEvent) {
        val s = _state.value
        if (!s.allowed || s.busy) return
        when (event) {
            is NewExpenseEvent.CompanyChosen -> _state.update { it.copy(companyCode = event.code, actionError = null) }
            is NewExpenseEvent.AmountChanged -> _state.update { it.copy(amount = event.value, actionError = null) }
            is NewExpenseEvent.DescriptionChanged -> _state.update { it.copy(description = event.value, actionError = null) }
            is NewExpenseEvent.ModeChosen -> _state.update { it.copy(mode = event.mode, actionError = null) }
            NewExpenseEvent.TakePhoto -> camera.take(
                onDone = { bytes -> if (bytes != null) _state.update { it.copy(photo = bytes, actionError = null) } },
                onError = { message -> _state.update { it.copy(actionError = message) } },
            )
            NewExpenseEvent.RemovePhoto -> _state.update { it.copy(photo = null) }
            NewExpenseEvent.Save -> save()
            NewExpenseEvent.Retry -> load()
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch {
            val company = s.companyCode
            if (company == null) {
                _state.update { it.copy(actionError = getString(Res.string.expense_need_company)) }
                return@launch
            }
            if ((s.amount ?: 0) <= 0) {
                _state.update { it.copy(actionError = getString(Res.string.expense_need_amount)) }
                return@launch
            }
            if (s.description.isBlank()) {
                _state.update { it.copy(actionError = getString(Res.string.expense_need_what)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                repository.addExpense(
                    ExpenseRequest(
                        companyCode = company,
                        amount = (s.amount ?: 0).toInt(),
                        description = s.description.trim(),
                        mode = s.mode,
                        photo = s.photo?.toBase64(),
                        requestId = requestId,
                    ),
                )
                // Saved, so a fresh expense gets a fresh id.
                requestId = randomUuid()
                app.message = getString(Res.string.expense_saved)
                _effects.trySend(NewExpenseEffect.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Airtel refuses anyone but the wallet holder, in the server's own words.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val companies = repository.myCompanies()
                _state.update { it.copy(loading = false, companies = companies, companyCode = it.companyCode ?: companies.singleOrNull()?.code) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}
