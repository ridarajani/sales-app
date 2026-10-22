package com.salesapp.ui.stock

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.CountLineRequest
import com.salesapp.data.CountRequest
import com.salesapp.data.CountResult
import com.salesapp.data.StockBatch
import com.salesapp.data.StockProduct
import com.salesapp.data.StockRepository
import com.salesapp.data.canKeepStock
import com.salesapp.ui.catalog.FormEffect
import com.salesapp.ui.catalog.companiesOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CountRow(val product: StockProduct, val batch: StockBatch)

data class StockCountState(
    val allowed: Boolean = true,
    val companies: List<String> = emptyList(),
    val company: String? = null,
    val loading: Boolean = false,
    val loadError: String? = null,
    val rows: List<CountRow> = emptyList(),
    // batch id -> (cartons, pieces) as typed.
    val counted: Map<Int, Pair<Int?, Int?>> = emptyMap(),
    val busy: Boolean = false,
    val error: String? = null,
    val result: CountResult? = null,
) {
    val hasCounts: Boolean get() = counted.values.any { it.first != null || it.second != null }
}

sealed interface StockCountEvent {
    data class CompanyChosen(val code: String) : StockCountEvent
    data class Cartons(val batchId: Int, val value: Int?) : StockCountEvent
    data class Pieces(val batchId: Int, val value: Int?) : StockCountEvent
    data object Save : StockCountEvent
    data object Retry : StockCountEvent
    data object Finish : StockCountEvent
}

class StockCountViewModel(
    private val repository: StockRepository,
    private val app: AppState,
) : ViewModel() {
    private val _state = MutableStateFlow(StockCountState())
    val state: StateFlow<StockCountState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FormEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<FormEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null

    init {
        val user = app.user
        val companies = companiesOf(user)
        _state.value = StockCountState(allowed = user?.canKeepStock() == true, companies = companies)
        if (companies.size == 1 && _state.value.allowed) chooseCompany(companies.first())
    }

    fun onEvent(event: StockCountEvent) {
        when (event) {
            is StockCountEvent.CompanyChosen -> if (event.code != _state.value.company) chooseCompany(event.code)
            is StockCountEvent.Cartons -> _state.update {
                val old = it.counted[event.batchId] ?: (null to null)
                it.copy(counted = it.counted + (event.batchId to (event.value to old.second)), error = null)
            }
            is StockCountEvent.Pieces -> _state.update {
                val old = it.counted[event.batchId] ?: (null to null)
                it.copy(counted = it.counted + (event.batchId to (old.first to event.value)), error = null)
            }
            StockCountEvent.Save -> save()
            StockCountEvent.Retry -> _state.value.company?.let { load(it) }
            StockCountEvent.Finish -> _effects.tryEmit(FormEffect.Done)
        }
    }

    private fun chooseCompany(code: String) {
        _state.update { it.copy(company = code, rows = emptyList(), counted = emptyMap(), error = null) }
        load(code)
    }

    private fun load(code: String) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val rows = repository.stock(code).flatMap { product -> product.batches.map { CountRow(product, it) } }
                _state.update { if (it.company == code) it.copy(loading = false, rows = rows) else it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = app.handleApiError(e)) }
            }
        }
    }

    private fun save() {
        val s = _state.value
        val company = s.company ?: return
        if (s.busy) return
        val lines = s.rows.mapNotNull { row ->
            val typed = s.counted[row.batch.id] ?: return@mapNotNull null
            if (typed.first == null && typed.second == null) return@mapNotNull null
            CountLineRequest(row.batch.id, typed.first ?: 0, typed.second ?: 0)
        }
        if (lines.isEmpty()) return
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            try {
                val result = repository.count(CountRequest(companyCode = company, lines = lines))
                _state.update { it.copy(result = result) }
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
