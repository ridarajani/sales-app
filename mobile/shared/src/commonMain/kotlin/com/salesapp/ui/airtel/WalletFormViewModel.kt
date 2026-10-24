package com.salesapp.ui.airtel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AirtelRepository
import com.salesapp.data.AppState
import com.salesapp.data.CashRepository
import com.salesapp.data.Company
import com.salesapp.data.UserRow
import com.salesapp.data.WalletRequest
import com.salesapp.data.canSetUpWallets
import com.salesapp.resources.Res
import com.salesapp.resources.airtel_admin_need_company
import com.salesapp.resources.airtel_admin_need_holder
import com.salesapp.resources.airtel_admin_saved
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

data class WalletFormState(
    val allowed: Boolean = true,
    val loading: Boolean = true,
    // Null when a new wallet is being set up.
    val walletId: Int? = null,
    val holders: List<UserRow> = emptyList(),
    val companies: List<Company> = emptyList(),
    val holderId: Int? = null,
    val openingBalance: Long? = null,
    val chosen: Set<String> = emptySet(),
    val active: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
    val actionError: String? = null,
)

sealed interface WalletFormEvent {
    data class HolderChosen(val id: Int) : WalletFormEvent
    data class OpeningChanged(val value: Long?) : WalletFormEvent
    data class CompanyToggled(val code: String) : WalletFormEvent
    data class ActiveChanged(val value: Boolean) : WalletFormEvent
    data object Save : WalletFormEvent
    data object Retry : WalletFormEvent
}

sealed interface WalletFormEffect {
    data object Saved : WalletFormEffect
}

class WalletFormViewModel(
    private val id: Int?,
    private val repository: AirtelRepository,
    private val cash: CashRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(WalletFormState(allowed = app.user?.canSetUpWallets() == true, walletId = id))
    val state: StateFlow<WalletFormState> = _state.asStateFlow()

    private val _effects = Channel<WalletFormEffect>(Channel.BUFFERED)
    val effects: Flow<WalletFormEffect> = _effects.receiveAsFlow()

    init {
        if (_state.value.allowed) load() else _state.update { it.copy(loading = false) }
    }

    fun onEvent(event: WalletFormEvent) {
        val s = _state.value
        if (!s.allowed || s.busy) return
        when (event) {
            is WalletFormEvent.HolderChosen -> _state.update { it.copy(holderId = event.id, actionError = null) }
            is WalletFormEvent.OpeningChanged -> _state.update { it.copy(openingBalance = event.value, actionError = null) }
            is WalletFormEvent.CompanyToggled -> _state.update {
                val chosen = if (event.code in it.chosen) it.chosen - event.code else it.chosen + event.code
                it.copy(chosen = chosen, actionError = null)
            }
            is WalletFormEvent.ActiveChanged -> _state.update { it.copy(active = event.value) }
            WalletFormEvent.Save -> save()
            WalletFormEvent.Retry -> load()
        }
    }

    private fun save() {
        val s = _state.value
        viewModelScope.launch {
            val holder = s.holderId
            if (holder == null) {
                _state.update { it.copy(actionError = getString(Res.string.airtel_admin_need_holder)) }
                return@launch
            }
            if (s.chosen.isEmpty()) {
                _state.update { it.copy(actionError = getString(Res.string.airtel_admin_need_company)) }
                return@launch
            }
            _state.update { it.copy(busy = true, actionError = null) }
            try {
                val request = WalletRequest(
                    holderId = holder,
                    openingBalance = (s.openingBalance ?: 0).toInt(),
                    companies = s.chosen.sorted(),
                    active = s.active,
                )
                if (s.walletId == null) repository.create(request) else repository.update(s.walletId, request)
                app.message = getString(Res.string.airtel_admin_saved)
                _effects.trySend(WalletFormEffect.Saved)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The server says when a company already has a wallet, or the holder is not a manager.
                val message = app.handleApiError(e)
                _state.update { it.copy(busy = false, actionError = message) }
            }
        }
    }

    private fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val holders = repository.possibleHolders()
                val companies = cash.myCompanies()
                val wallet = id?.let { repository.wallet(it) }
                _state.update {
                    it.copy(
                        loading = false,
                        holders = holders,
                        companies = companies,
                        holderId = wallet?.holderId ?: it.holderId,
                        openingBalance = wallet?.openingBalance?.toLong() ?: it.openingBalance,
                        chosen = wallet?.companies?.toSet() ?: it.chosen,
                        active = wallet?.active ?: it.active,
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
