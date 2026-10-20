package com.salesapp.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
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
    ): HttpResponse {
        val response = try {
            client.request {
                this.method = method
                url(baseUrlProvider().trimEnd('/') + path)
                query.forEach { (key, value) -> if (value != null) parameter(key, value) }
                tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
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

    private suspend inline fun <reified T, reified B : Any> post(path: String, body: B?): T =
        decode { send(HttpMethod.Post, path, body).body<T>() }

    private suspend inline fun <reified T, reified B : Any> put(path: String, body: B?): T =
        decode { send(HttpMethod.Put, path, body).body<T>() }

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
}
