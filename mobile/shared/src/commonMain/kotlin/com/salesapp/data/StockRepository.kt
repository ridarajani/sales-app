package com.salesapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

// Where the stock screens get their data. Keeps the last stock list so a product or batch can be shown without loading again.
class StockRepository(private val api: Api, app: AppState) {
    private val _products = MutableStateFlow<List<StockProduct>>(emptyList())
    val products: StateFlow<List<StockProduct>> = _products.asStateFlow()

    init {
        app.onSessionChanged { clear() }
    }

    fun clear() {
        _products.value = emptyList()
    }

    suspend fun stock(company: String? = null, q: String? = null): List<StockProduct> {
        val list = api.stock(company, q, includeEmpty = false)
        _products.value = list
        return list
    }

    fun cachedProduct(productId: Int): StockProduct? = _products.value.firstOrNull { it.productId == productId }

    fun cachedBatch(batchId: Int): Pair<StockProduct, StockBatch>? {
        for (product in _products.value) {
            val batch = product.batches.firstOrNull { it.id == batchId }
            if (batch != null) return product to batch
        }
        return null
    }

    suspend fun product(productId: Int): StockProduct? =
        cachedProduct(productId) ?: stock().firstOrNull { it.productId == productId }

    suspend fun batch(batchId: Int): Pair<StockProduct, StockBatch>? {
        cachedBatch(batchId)?.let { return it }
        stock()
        return cachedBatch(batchId)
    }

    suspend fun shortDated(company: String? = null, days: Int? = null): List<ShortDatedBatch> = api.shortDated(company, days)

    suspend fun movements(batchId: Int): List<StockMovement> = api.batchMovements(batchId)

    // The products a company sells, for picking what arrived (includes products with no stock yet).
    suspend fun products(company: String?): List<Product> = api.products(null, company, false)

    suspend fun receive(request: ReceiptRequest): ReceiptDetail {
        val result = api.receiveStock(request)
        clear()
        return result
    }

    suspend fun adjust(request: AdjustRequest): AdjustResult {
        val result = api.adjustStock(request)
        clear()
        return result
    }

    suspend fun count(request: CountRequest): CountResult {
        val result = api.countStock(request)
        clear()
        return result
    }
}
