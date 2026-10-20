package com.salesapp.data

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.header
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

// A problem reported by the server, or a lost connection.
class ApiException(val code: String, override val message: String, val status: Int = 0) : Exception(message) {
    val isSignedOut: Boolean get() = status == 401 || code == "not_signed_in"
}

private const val GENERIC_MESSAGE = "Something went wrong. Try again."
private const val OFFLINE_MESSAGE = "No connection. Check the internet and try again."

private val json = Json {
    ignoreUnknownKeys = true
    coerceInputValues = true
}

// Talks to the server. One function for each thing the server can do.
class Api(private val baseUrl: String, private val tokenProvider: () -> String?) {
    private val client = HttpClient {
        expectSuccess = false
        install(ContentNegotiation) { json(json) }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 15_000
            socketTimeoutMillis = 15_000
        }
    }

    private suspend inline fun <reified B : Any> send(method: HttpMethod, path: String, body: B?): HttpResponse {
        val response = try {
            client.request {
                this.method = method
                url(baseUrl.trimEnd('/') + path)
                tokenProvider()?.let { header(HttpHeaders.Authorization, "Bearer $it") }
                if (body != null) {
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ApiException("offline", OFFLINE_MESSAGE)
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

    private suspend inline fun <reified T> get(path: String): T =
        decode { send<Unit>(HttpMethod.Get, path, null).body<T>() }

    private suspend inline fun <reified T, reified B : Any> post(path: String, body: B?): T =
        decode { send(HttpMethod.Post, path, body).body<T>() }

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
}
