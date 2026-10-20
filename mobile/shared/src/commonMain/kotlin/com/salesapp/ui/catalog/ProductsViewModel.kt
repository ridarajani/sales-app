package com.salesapp.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.ListsRepository
import com.salesapp.data.Product
import com.salesapp.data.canEditProducts
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProductsState(
    val query: String = "",
    val company: String? = null,
    val includeInactive: Boolean = false,
    val loading: Boolean = true,
    val products: List<Product> = emptyList(),
    val error: String? = null,
    val companies: List<String> = emptyList(),
    val canEdit: Boolean = false,
)

sealed interface ProductsEvent {
    data class QueryChanged(val query: String) : ProductsEvent
    data class CompanyChosen(val code: String?) : ProductsEvent
    data object ToggleInactive : ProductsEvent
    data object Retry : ProductsEvent
    data object Refresh : ProductsEvent
    data class OpenProduct(val id: Int) : ProductsEvent
    data object AddProduct : ProductsEvent
}

sealed interface ProductsEffect {
    data class OpenProduct(val id: Int) : ProductsEffect
    data object AddProduct : ProductsEffect
}

class ProductsViewModel(
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val user = app.session.user.value
    private val _state = MutableStateFlow(ProductsState(companies = companiesOf(user), canEdit = user?.canEditProducts() == true))
    val state: StateFlow<ProductsState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<ProductsEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<ProductsEffect> = _effects.asSharedFlow()

    private var loadJob: Job? = null
    private var loadedOnce = false

    init {
        load()
    }

    fun onEvent(event: ProductsEvent) {
        when (event) {
            is ProductsEvent.QueryChanged -> {
                _state.update { it.copy(query = event.query) }
                load(delayMillis = 300, showSpinner = false)
            }
            is ProductsEvent.CompanyChosen -> {
                _state.update { it.copy(company = event.code) }
                load()
            }
            ProductsEvent.ToggleInactive -> {
                _state.update { it.copy(includeInactive = !it.includeInactive) }
                load()
            }
            ProductsEvent.Retry -> load()
            ProductsEvent.Refresh -> if (loadedOnce) load(showSpinner = false)
            is ProductsEvent.OpenProduct -> if (_state.value.canEdit) _effects.tryEmit(ProductsEffect.OpenProduct(event.id))
            ProductsEvent.AddProduct -> _effects.tryEmit(ProductsEffect.AddProduct)
        }
    }

    private fun load(delayMillis: Long = 0, showSpinner: Boolean = true) {
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            if (delayMillis > 0) delay(delayMillis)
            if (showSpinner) _state.update { it.copy(loading = true, error = null) }
            val now = _state.value
            try {
                val rows = repository.products(now.query.trim().ifEmpty { null }, now.company, now.includeInactive)
                loadedOnce = true
                _state.update { it.copy(loading = false, products = rows, error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = app.handleApiError(e)) }
            }
        }
    }
}
