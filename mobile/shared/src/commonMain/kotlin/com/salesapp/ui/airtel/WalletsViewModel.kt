package com.salesapp.ui.airtel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AirtelRepository
import com.salesapp.data.AppState
import com.salesapp.data.WalletCheckRequest
import com.salesapp.data.WalletDetail
import com.salesapp.data.WalletListItem
import com.salesapp.data.WithdrawRequest
import com.salesapp.data.canSeeWallets
import com.salesapp.data.canSetUpWallets
import com.salesapp.resources.Res
import com.salesapp.resources.airtel_check_need
import com.salesapp.resources.airtel_withdraw_need_amount
import com.salesapp.resources.airtel_withdrew
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.getString

data class WalletsState(
    val allowed: Boolean = true,
    val canSetUp: Boolean = false,
    val loading: Boolean = true,
    val wallets: List<WalletListItem> = emptyList(),
    val error: String? = null,
)

sealed interface WalletsEvent {
    data object Retry : WalletsEvent
    data object Refresh : WalletsEvent
}

class WalletsViewModel(
    private val repository: AirtelRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(
        WalletsState(allowed = app.user?.canSeeWallets() == true, canSetUp = app.user?.canSetUpWallets() == true),
    )
    val state: StateFlow<WalletsState> = _state.asStateFlow()

    private var loadedOnce = false

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: WalletsEvent) {
        if (!_state.value.allowed) return
        when (event) {
            WalletsEvent.Retry -> load()
            WalletsEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
        }
    }

    private fun load(showSpinner: Boolean = true) {
        viewModelScope.launch {
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            try {
                val rows = repository.wallets()
                loadedOnce = true
                _state.update { it.copy(loading = false, wallets = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(loading = false, error = message) }
            }
        }
    }
}

// Which extra form is open on the wallet, if any.
enum class WalletForm { Withdraw, Check }

data class WalletDetailState(
    val loading: Boolean = true,
    // Only admin may change who holds the wallet.
    val canSetUp: Boolean = false,
    val wallet: WalletDetail? = null,
    val form: WalletForm? = null,
    // Taking cash out.
    val withdrawCompany: String? = null,
    val withdrawAmount: Long? = null,
    val withdrawCharge: Long? = null,
    // Checking against the phone.
    val actual: Long? = null,
    val checkNote: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
) {
    val canAct: Boolean get() = wallet?.canAct == true

    // Admin can tick and check for the holder, but the cash only ever goes into the holder's hand.
    val canTakeOut: Boolean get() = wallet?.canTakeOut == true
}

sealed interface WalletDetailEvent {
    data object Retry : WalletDetailEvent
    data object Refresh : WalletDetailEvent
    data class Tick(val entryId: Int) : WalletDetailEvent
    data class OpenForm(val form: WalletForm?) : WalletDetailEvent
    data class WithdrawCompanyChosen(val code: String) : WalletDetailEvent
    data class WithdrawAmountChanged(val value: Long?) : WalletDetailEvent
    data class WithdrawChargeChanged(val value: Long?) : WalletDetailEvent
    data object Withdraw : WalletDetailEvent
    data class ActualChanged(val value: Long?) : WalletDetailEvent
    data class CheckNoteChanged(val value: String) : WalletDetailEvent
    data object Check : WalletDetailEvent
}

class WalletDetailViewModel(
    private val id: Int,
    private val repository: AirtelRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(WalletDetailState(canSetUp = app.user?.canSetUpWallets() == true))
    val state: StateFlow<WalletDetailState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: WalletDetailEvent) {
        val s = _state.value
        if (s.busy && event != WalletDetailEvent.Retry) return
        when (event) {
            WalletDetailEvent.Retry -> load()
            WalletDetailEvent.Refresh -> if (s.wallet != null && !s.loading) load(showSpinner = false)
            is WalletDetailEvent.Tick -> act { repository.tick(event.entryId) }
            is WalletDetailEvent.OpenForm -> if (event.form != WalletForm.Withdraw || s.canTakeOut) _state.update {
                it.copy(
                    form = event.form,
                    actionError = null,
                    withdrawCompany = it.withdrawCompany ?: it.wallet?.companies?.firstOrNull(),
                )
            }
            is WalletDetailEvent.WithdrawCompanyChosen -> _state.update { it.copy(withdrawCompany = event.code, actionError = null) }
            is WalletDetailEvent.WithdrawAmountChanged -> _state.update { it.copy(withdrawAmount = event.value, actionError = null) }
            is WalletDetailEvent.WithdrawChargeChanged -> _state.update { it.copy(withdrawCharge = event.value, actionError = null) }
            WalletDetailEvent.Withdraw -> withdraw()
            is WalletDetailEvent.ActualChanged -> _state.update { it.copy(actual = event.value, actionError = null) }
            is WalletDetailEvent.CheckNoteChanged -> _state.update { it.copy(checkNote = event.value) }
            WalletDetailEvent.Check -> check()
        }
    }

    private fun withdraw() {
        val s = _state.value
        if (!s.canTakeOut) return
        val company = s.withdrawCompany ?: s.wallet?.companies?.firstOrNull() ?: return
        viewModelScope.launch {
            if ((s.withdrawAmount ?: 0) <= 0) {
                _state.update { it.copy(actionError = getString(Res.string.airtel_withdraw_need_amount)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val wallet = repository.withdraw(
                    id,
                    WithdrawRequest(
                        companyCode = company,
                        amount = (s.withdrawAmount ?: 0).toInt(),
                        charge = (s.withdrawCharge ?: 0).toInt(),
                    ),
                )
                app.message = getString(Res.string.airtel_withdrew)
                _state.update {
                    it.copy(busy = false, wallet = wallet, form = null, withdrawAmount = null, withdrawCharge = null)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server says when the wallet does not have that much.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun check() {
        val s = _state.value
        viewModelScope.launch {
            val actual = s.actual
            if (actual == null) {
                _state.update { it.copy(actionError = getString(Res.string.airtel_check_need)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val wallet = repository.check(id, WalletCheckRequest(actual = actual.toInt(), note = s.checkNote.trim()))
                // The newest check is first, so the difference shows at the top of the list.
                _state.update { it.copy(busy = false, wallet = wallet, form = null, actual = null, checkNote = "") }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun act(call: suspend () -> WalletDetail) {
        if (_state.value.busy) return
        _state.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            try {
                val wallet = call()
                _state.update { it.copy(busy = false, wallet = wallet) }
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
                val wallet = repository.wallet(id)
                _state.update {
                    it.copy(
                        loading = false,
                        wallet = wallet,
                        withdrawCompany = it.withdrawCompany ?: wallet.companies.firstOrNull(),
                        error = null,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = app.handleApiError(e)
                _state.update { if (it.wallet == null) it.copy(loading = false, error = message) else it.copy(loading = false) }
            }
        }
    }
}
