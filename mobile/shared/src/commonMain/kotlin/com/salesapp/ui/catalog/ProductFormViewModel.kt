package com.salesapp.ui.catalog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.salesapp.data.AppState
import com.salesapp.data.ListsRepository
import com.salesapp.data.Product
import com.salesapp.data.ProductRequest
import com.salesapp.resources.Res
import com.salesapp.resources.catalog_added
import com.salesapp.resources.catalog_saved
import com.salesapp.resources.product_err_brand
import com.salesapp.resources.product_err_code
import com.salesapp.resources.product_err_company
import com.salesapp.resources.product_err_name
import com.salesapp.resources.product_err_pack
import com.salesapp.resources.product_err_pieces
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

data class ProductFormState(
    val isEdit: Boolean = false,
    val loading: Boolean = false,
    val loadError: String? = null,
    val companies: List<String> = emptyList(),
    val company: String? = null,
    val brand: String = "",
    val name: String = "",
    val packSize: String = "",
    val pieces: String = "",
    val code: String = "",
    val active: Boolean = true,
    val busy: Boolean = false,
    val error: String? = null,
)

sealed interface ProductFormEvent {
    data class Company(val code: String) : ProductFormEvent
    data class Brand(val value: String) : ProductFormEvent
    data class Name(val value: String) : ProductFormEvent
    data class Pack(val value: String) : ProductFormEvent
    data class Pieces(val value: String) : ProductFormEvent
    data class Code(val value: String) : ProductFormEvent
    data class Active(val value: Boolean) : ProductFormEvent
    data object Save : ProductFormEvent
    data object Retry : ProductFormEvent
}

sealed interface FormEffect {
    data object Done : FormEffect
}

class ProductFormViewModel(
    private val id: Int?,
    private val repository: ListsRepository,
    private val app: AppState,
) : ViewModel() {
    private val companies = companiesOf(app.session.user.value)
    private val _state = MutableStateFlow(
        ProductFormState(
            isEdit = id != null,
            loading = id != null,
            companies = companies,
            company = companies.singleOrNull(),
        ),
    )
    val state: StateFlow<ProductFormState> = _state.asStateFlow()

    private val _effects = MutableSharedFlow<FormEffect>(extraBufferCapacity = 1)
    val effects: SharedFlow<FormEffect> = _effects.asSharedFlow()

    private var loaded: Product? = null

    init {
        if (id != null) load(id)
    }

    fun onEvent(event: ProductFormEvent) {
        when (event) {
            is ProductFormEvent.Company -> _state.update { it.copy(company = event.code, error = null) }
            is ProductFormEvent.Brand -> _state.update { it.copy(brand = event.value, error = null) }
            is ProductFormEvent.Name -> _state.update { it.copy(name = event.value, error = null) }
            is ProductFormEvent.Pack -> _state.update { it.copy(packSize = event.value, error = null) }
            is ProductFormEvent.Pieces -> _state.update { it.copy(pieces = event.value.filter { c -> c.isDigit() }.take(6), error = null) }
            is ProductFormEvent.Code -> _state.update { it.copy(code = event.value.uppercase().filter { c -> c.isLetterOrDigit() || c == '-' || c == '_' }, error = null) }
            is ProductFormEvent.Active -> _state.update { it.copy(active = event.value) }
            ProductFormEvent.Save -> save()
            ProductFormEvent.Retry -> if (id != null) load(id)
        }
    }

    private fun load(productId: Int) {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, loadError = null) }
            try {
                val p = repository.product(productId)
                loaded = p
                _state.update {
                    it.copy(
                        loading = false,
                        company = p.companyCode,
                        brand = p.brand,
                        name = p.name,
                        packSize = p.packSize,
                        pieces = p.piecesPerCarton.toString(),
                        code = p.code,
                        active = p.active,
                    )
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
        // Mark as saving at once so a double tap cannot save twice.
        _state.update { it.copy(busy = true, error = null) }
        viewModelScope.launch {
            val pieces = s.pieces.toIntOrNull()
            val problem = when {
                s.company == null -> getString(Res.string.product_err_company)
                s.brand.isBlank() -> getString(Res.string.product_err_brand)
                s.name.isBlank() -> getString(Res.string.product_err_name)
                s.packSize.isBlank() -> getString(Res.string.product_err_pack)
                pieces == null || pieces < 1 || pieces > 100000 -> getString(Res.string.product_err_pieces)
                s.code.isBlank() -> getString(Res.string.product_err_code)
                else -> null
            }
            if (problem != null) {
                _state.update { it.copy(busy = false, error = problem) }
                return@launch
            }
            try {
                val old = loaded
                val request = ProductRequest(
                    code = s.code.trim().takeIf { old == null || it != old.code },
                    companyCode = s.company.takeIf { old == null || it != old.companyCode },
                    brand = s.brand.trim().takeIf { old == null || it != old.brand },
                    name = s.name.trim().takeIf { old == null || it != old.name },
                    packSize = s.packSize.trim().takeIf { old == null || it != old.packSize },
                    piecesPerCarton = pieces.takeIf { old == null || it != old.piecesPerCarton },
                    active = if (old != null && s.active != old.active) s.active else null,
                )
                if (id == null) {
                    val added = repository.createProduct(request)
                    app.message = getString(Res.string.catalog_added, added.name)
                } else {
                    repository.updateProduct(id, request)
                    app.message = getString(Res.string.catalog_saved)
                }
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
