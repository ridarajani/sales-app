package com.salesapp.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// A problem reported by the server, or a lost connection.
class ApiException(val code: String, override val message: String, val status: Int = 0) : Exception(message) {
    val isSignedOut: Boolean get() = status == 401 || code == "not_signed_in"
}

private const val GENERIC_MESSAGE = "Something went wrong. Try again."
private const val OFFLINE_MESSAGE = "No connection. Check the internet and try again."

// Calls that carry a photo or a PDF. A phone on a slow line needs far more than the usual 15 seconds.
const val FILE_TIMEOUT: Long = 60_000

private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
    // Fields that are null are left out of what we send.
    explicitNulls = false
}

// Talks to the server. One function for each thing the server can do.
class Api(private val baseUrlProvider: () -> String, private val tokenProvider: () -> String?) {
    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    private suspend inline fun <reified B : Any> send(
        method: HttpMethod,
        path: String,
        body: B?,
        query: Map<String, String?> = emptyMap(),
        timeoutMillis: Long = 0,
    ): HttpResponse {
        val response = try {
            client.request {
                this.method = method
                url(baseUrlProvider().trimEnd('/') + path)
                query.forEach { (key, value) -> if (value != null) parameter(key, value) }
                tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                // Photos and PDFs take much longer than a list, so those calls ask for more time.
                if (timeoutMillis > 0) {
                    timeout {
                        requestTimeoutMillis = timeoutMillis
                        socketTimeoutMillis = timeoutMillis
                    }
                }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Keep the plain message first; the technical reason underneath helps whoever is testing.
            val reason = e.message?.take(160) ?: (e::class.simpleName ?: "unknown")
            throw ApiException("offline", "$OFFLINE_MESSAGE\nReason: $reason")
        }
        if (!response.status.isSuccess()) throw errorFrom(response)
        return response
    }

    private suspend fun errorFrom(response: HttpResponse): ApiException {
        val error = try {
            response.body<ApiError>()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ApiError()
        }
        val code = error.error.ifBlank { "http_${response.status.value}" }
        return ApiException(code, error.message.ifBlank { GENERIC_MESSAGE }, response.status.value)
    }

    private suspend inline fun <reified T> get(path: String, query: Map<String, String?> = emptyMap()): T =
        decode { send<Unit>(HttpMethod.Get, path, null, query).body<T>() }

    private suspend inline fun <reified T, reified B : Any> post(path: String, body: B?, timeoutMillis: Long = 0): T =
        decode { send(HttpMethod.Post, path, body, timeoutMillis = timeoutMillis).body<T>() }

    private suspend inline fun <reified T, reified B : Any> put(path: String, body: B?): T =
        decode { send(HttpMethod.Put, path, body).body<T>() }

    private suspend inline fun <reified T, reified B : Any> patch(path: String, body: B?): T =
        decode { send(HttpMethod.Patch, path, body).body<T>() }

    // No body, so no Content-Type: the server rejects an empty JSON body.
    private suspend inline fun <reified T> delete(path: String): T =
        decode { send<Unit>(HttpMethod.Delete, path, null).body<T>() }

    // A file from the server (a PDF, a signature or a photo), with the auth header. The server
    // decides the type, so the caller is told it along with the bytes.
    private suspend fun bytes(path: String, query: Map<String, String?> = emptyMap()): ServerFile {
        val response = send<Unit>(HttpMethod.Get, path, null, query, timeoutMillis = FILE_TIMEOUT)
        return ServerFile(response.readRawBytes(), response.contentType()?.toString() ?: "application/octet-stream")
    }

    // Stops the connection and frees its resources.
    fun close() {
        client.close()
    }

    private suspend inline fun <T> decode(block: () -> T): T = try {
        block()
    } catch (e: ApiException) {
        throw e
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        throw ApiException("bad_reply", GENERIC_MESSAGE)
    }

    suspend fun login(phone: String, pin: String): LoginResponse = post<LoginResponse, LoginRequest>("/auth/login", LoginRequest(phone, pin))

    suspend fun checkPhone(phone: String): PhoneCheck =
        post<PhoneCheck, PhoneRequest>("/auth/check-phone", PhoneRequest(phone))

    suspend fun logout() {
        send<Unit>(HttpMethod.Post, "/auth/logout", null)
    }

    suspend fun me(): User = get("/auth/me")

    suspend fun users(): List<UserRow> = get("/users")

    suspend fun createUser(request: NewUserRequest): UserRow = post<UserRow, NewUserRequest>("/users", request)

    suspend fun switchOff(id: Int): UserRow = post<UserRow, Unit>("/users/$id/switch-off", null)

    suspend fun switchOn(id: Int): UserRow = post<UserRow, Unit>("/users/$id/switch-on", null)

    suspend fun resetPin(id: Int, pin: String) {
        send(HttpMethod.Post, "/users/$id/reset-pin", ResetPinRequest(pin))
    }

    suspend fun companies(): List<Company> = get("/companies")

    // Returns null when the server has no version for this phone type yet.
    suspend fun appVersion(platform: String): AppVersion? = try {
        get<AppVersion>("/app/version?platform=$platform")
    } catch (e: ApiException) {
        if (e.code == "no_version" || e.code == "http_404") null else throw e
    }

    // Customers, products, vehicles and companies.

    suspend fun customers(q: String?, company: String?, includeInactive: Boolean): List<Customer> =
        get("/customers", mapOf("q" to q?.trim()?.ifEmpty { null }, "company" to company, "includeInactive" to if (includeInactive) "true" else null))

    suspend fun customer(id: Int): Customer = get("/customers/$id")

    suspend fun createCustomer(request: CustomerRequest): Customer = post<Customer, CustomerRequest>("/customers", request)

    suspend fun updateCustomer(id: Int, request: CustomerRequest): Customer = put<Customer, CustomerRequest>("/customers/$id", request)

    suspend fun products(q: String?, company: String?, includeInactive: Boolean): List<Product> =
        get("/products", mapOf("q" to q?.trim()?.ifEmpty { null }, "company" to company, "includeInactive" to if (includeInactive) "true" else null))

    suspend fun product(id: Int): Product = get("/products/$id")

    suspend fun createProduct(request: ProductRequest): Product = post<Product, ProductRequest>("/products", request)

    suspend fun updateProduct(id: Int, request: ProductRequest): Product = put<Product, ProductRequest>("/products/$id", request)

    suspend fun vehicles(): List<Vehicle> = get("/vehicles")

    suspend fun drivers(): List<Driver> = get("/drivers")

    suspend fun createVehicle(request: VehicleRequest): Vehicle = post<Vehicle, VehicleRequest>("/vehicles", request)

    // Build the body with vehicleChange(...).
    suspend fun updateVehicle(id: Int, change: JsonObject): Vehicle = put<Vehicle, JsonObject>("/vehicles/$id", change)

    suspend fun companyDetails(code: String): CompanyDetails = get("/companies/$code")

    // Build the body with companyChange(...).
    suspend fun updateCompany(code: String, change: JsonObject): CompanyDetails =
        put<CompanyDetails, JsonObject>("/companies/$code", change)

    suspend fun openingBalances(): List<OpeningBalance> = get("/opening-balances")

    suspend fun setOpeningBalance(customerId: Int, request: OpeningBalanceRequest): OpeningBalance =
        put<OpeningBalance, OpeningBalanceRequest>("/customers/$customerId/opening-balance", request)

    // Stock.

    suspend fun stock(company: String?, q: String?, includeEmpty: Boolean): List<StockProduct> =
        get("/stock", mapOf("company" to company, "q" to q?.trim()?.ifEmpty { null }, "includeEmpty" to if (includeEmpty) "true" else null))

    suspend fun shortDated(company: String?, days: Int? = null): List<ShortDatedBatch> =
        get("/stock/short-dated", mapOf("company" to company, "days" to days?.toString()))

    suspend fun batchMovements(batchId: Int): List<StockMovement> = get("/stock/batches/$batchId/movements")

    suspend fun receiveStock(request: ReceiptRequest): ReceiptDetail = post<ReceiptDetail, ReceiptRequest>("/stock/receipts", request)

    suspend fun adjustStock(request: AdjustRequest): AdjustResult = post<AdjustResult, AdjustRequest>("/stock/adjustments", request)

    suspend fun pendingAdjustments(): List<PendingAdjustment> = get("/stock/adjustments/pending")

    suspend fun approveAdjustment(id: Int, note: String = ""): AdjustmentDecision =
        post<AdjustmentDecision, DecisionRequest>("/stock/adjustments/$id/approve", DecisionRequest(note))

    suspend fun rejectAdjustment(id: Int, note: String = ""): AdjustmentDecision =
        post<AdjustmentDecision, DecisionRequest>("/stock/adjustments/$id/reject", DecisionRequest(note))

    suspend fun countStock(request: CountRequest): CountResult = post<CountResult, CountRequest>("/stock/counts", request)

    // Orders.

    // status: open, hold, done or cancelled (null for all); q matches the order number or customer name.
    suspend fun orders(status: String?, q: String?, company: String?): List<OrderListItem> =
        get("/orders", mapOf("status" to status, "q" to q?.trim()?.ifEmpty { null }, "company" to company))

    suspend fun booking(customerId: Int): BookingInfo = get("/orders/booking/$customerId")

    suspend fun createOrder(request: NewOrderRequest): OrderDetail = post<OrderDetail, NewOrderRequest>("/orders", request)

    suspend fun order(id: Int): OrderDetail = get("/orders/$id")

    suspend fun changeOrder(id: Int, change: OrderChange): OrderDetail = patch<OrderDetail, OrderChange>("/orders/$id", change)

    suspend fun reserveOrder(id: Int, note: String = ""): OrderDetail = post<OrderDetail, OrderNote>("/orders/$id/reserve", OrderNote(note))

    suspend fun holdOrder(id: Int, note: String = ""): OrderDetail = post<OrderDetail, OrderNote>("/orders/$id/hold", OrderNote(note))

    suspend fun releaseOrder(id: Int, note: String = ""): OrderDetail = post<OrderDetail, OrderNote>("/orders/$id/release", OrderNote(note))

    suspend fun closeOrder(id: Int, note: String = ""): OrderDetail = post<OrderDetail, OrderNote>("/orders/$id/close", OrderNote(note))

    suspend fun cancelOrder(id: Int, note: String = ""): OrderDetail = post<OrderDetail, OrderNote>("/orders/$id/cancel", OrderNote(note))

    // Loading plans.

    // status: planned, loaded, out or done (null for all); facility: F1 or F2 (null for all).
    suspend fun loadingPlans(status: String?, facility: String?): List<LoadingPlanListItem> =
        get("/loading-plans", mapOf("status" to status, "facility" to facility))

    suspend fun loadingPlan(id: Int): PlanDetail = get("/loading-plans/$id")

    suspend fun createPlan(request: NewPlanRequest): PlanDetail = post<PlanDetail, NewPlanRequest>("/loading-plans", request)

    suspend fun openLines(id: Int): List<OpenProduct> = get("/loading-plans/$id/open-lines")

    suspend fun savePlanItems(id: Int, request: PlanItemsRequest): PlanDetail =
        put<PlanDetail, PlanItemsRequest>("/loading-plans/$id/items", request)

    // send, load, gate and seen have no body.
    suspend fun sendPlan(id: Int): PlanDetail = post<PlanDetail, Unit>("/loading-plans/$id/send", null)

    suspend fun loadPlan(id: Int): PlanDetail = post<PlanDetail, Unit>("/loading-plans/$id/load", null)

    suspend fun gatePlan(id: Int): PlanDetail = post<PlanDetail, Unit>("/loading-plans/$id/gate", null)

    suspend fun seenPlan(id: Int): PlanDetail = post<PlanDetail, Unit>("/loading-plans/$id/seen", null)

    suspend fun warehouseChange(id: Int, request: WarehouseChangeRequest): PlanDetail =
        patch<PlanDetail, WarehouseChangeRequest>("/loading-plans/$id/warehouse", request)

    suspend fun deletePlan(id: Int) {
        delete<JsonObject>("/loading-plans/$id")
    }

    // Phase 6: invoices and delivery.

    // Makes the invoices for the user's own companies from a loaded plan. The reply is the plan
    // again, with `made` listing what this call made.
    suspend fun makeInvoices(planId: Int): PlanDetail = post<PlanDetail, Unit>("/loading-plans/$planId/invoices", null)

    suspend fun invoices(query: String?, company: String?, type: String?): List<InvoiceListItem> =
        get("/invoices", mapOf("q" to query, "company" to company, "type" to type))

    suspend fun invoice(id: Int): InvoiceDetail = get("/invoices/$id")

    suspend fun invoicePdf(id: Int): ServerFile = bytes("/invoices/$id/pdf")

    suspend fun deliveryNotePdf(planId: Int): ServerFile = bytes("/loading-plans/$planId/delivery-note.pdf")

    suspend fun gatePassPdf(planId: Int): ServerFile = bytes("/loading-plans/$planId/gate-pass.pdf")

    suspend fun deliverySignature(deliveryId: Int): ServerFile = bytes("/deliveries/$deliveryId/signature")

    suspend fun deliveryPhoto(deliveryId: Int): ServerFile = bytes("/deliveries/$deliveryId/photo")

    // Sends a signature and often a photo, so it gets the longer timeout.
    suspend fun confirmDelivery(planId: Int, request: DeliveryRequest): PlanDetail =
        post<PlanDetail, DeliveryRequest>("/loading-plans/$planId/deliveries", request, timeoutMillis = FILE_TIMEOUT)

    // Phase 7: payments, expenses, cash and Airtel Money.

    // What the collect screen needs once a customer is picked.
    suspend fun collectInfo(customerId: Int): CollectInfo = get("/collections/customer/$customerId")

    // Carries a signature and often a photo of the proof, so it gets the longer timeout.
    suspend fun collect(request: CollectRequest): PaymentDetail =
        post<PaymentDetail, CollectRequest>("/collections", request, timeoutMillis = FILE_TIMEOUT)

    suspend fun collections(query: String?, mode: String?, company: String?): List<PaymentListItem> =
        get("/collections", mapOf("q" to query, "mode" to mode, "company" to company))

    suspend fun collection(id: Int): PaymentDetail = get("/collections/$id")

    suspend fun collectionProof(id: Int): ServerFile = bytes("/collections/$id/proof")

    suspend fun collectionSignature(id: Int): ServerFile = bytes("/collections/$id/signature")

    // May carry a photo of the bill.
    suspend fun addExpense(request: ExpenseRequest): Expense =
        post<Expense, ExpenseRequest>("/expenses", request, timeoutMillis = FILE_TIMEOUT)

    suspend fun expenses(company: String?): List<Expense> = get("/expenses", mapOf("company" to company))

    suspend fun expensePhoto(id: Int): ServerFile = bytes("/expenses/$id/photo")

    suspend fun myCash(): CashHeld = get("/cash/mine")

    suspend fun handOver(): HandoverDetail = post<HandoverDetail, Unit>("/handovers", null)

    // status: handed or counted; null for both.
    suspend fun handovers(status: String?): List<HandoverListItem> = get("/handovers", mapOf("status" to status))

    suspend fun handover(id: Int): HandoverDetail = get("/handovers/$id")

    suspend fun countHandover(id: Int, request: CashCountRequest): HandoverDetail =
        post<HandoverDetail, CashCountRequest>("/handovers/$id/count", request)

    suspend fun wallets(): List<WalletListItem> = get("/airtel/wallets")

    suspend fun wallet(id: Int): WalletDetail = get("/airtel/wallets/$id")

    suspend fun tickEntry(entryId: Int): WalletDetail = post<WalletDetail, Unit>("/airtel/entries/$entryId/tick", null)

    suspend fun withdraw(walletId: Int, request: WithdrawRequest): WalletDetail =
        post<WalletDetail, WithdrawRequest>("/airtel/wallets/$walletId/withdrawals", request)

    suspend fun checkWallet(walletId: Int, request: WalletCheckRequest): WalletDetail =
        post<WalletDetail, WalletCheckRequest>("/airtel/wallets/$walletId/checks", request)

    suspend fun createWallet(request: WalletRequest): WalletDetail = post<WalletDetail, WalletRequest>("/airtel/wallets", request)

    suspend fun updateWallet(id: Int, request: WalletRequest): WalletDetail =
        put<WalletDetail, WalletRequest>("/airtel/wallets/$id", request)

    // Phase 8: returns, notes, the ledger and cancelling a payment.

    // Goods given back after delivery. Booked as it is saved, so the invoice goes down at once.
    suspend fun addReturn(invoiceId: Int, request: ReturnRequest): ReturnDetail =
        post<ReturnDetail, ReturnRequest>("/invoices/$invoiceId/returns", request)

    // status: to_book, waiting, done or all.
    suspend fun returns(status: String, query: String?): List<ReturnListItem> =
        get("/returns", mapOf("status" to status, "q" to query))

    suspend fun salesReturn(id: Int): ReturnDetail = get("/returns/$id")

    suspend fun bookReturn(id: Int): ReturnDetail = post<ReturnDetail, Unit>("/returns/$id/book", null)

    suspend fun receiveReturn(id: Int, request: ReceiveRequest): ReturnDetail =
        post<ReturnDetail, ReceiveRequest>("/returns/$id/receive", request)

    suspend fun addNote(invoiceId: Int, request: NoteRequest): InvoiceNoteDetail =
        post<InvoiceNoteDetail, NoteRequest>("/invoices/$invoiceId/notes", request)

    // from and to are dates; company is required.
    suspend fun ledger(customerId: Int, company: String, from: String?, to: String?): Ledger =
        get("/customers/$customerId/ledger", mapOf("company" to company, "from" to from, "to" to to))

    suspend fun statementPdf(customerId: Int, company: String, from: String?, to: String?): ServerFile =
        bytes("/customers/$customerId/statement.pdf", mapOf("company" to company, "from" to from, "to" to to))

    suspend fun cancelPayment(id: Int, request: CancelRequest): PaymentDetail =
        post<PaymentDetail, CancelRequest>("/collections/$id/cancel", request)
}

// A file the server sent, with the type it gave it.
class ServerFile(val bytes: ByteArray, val contentType: String)
