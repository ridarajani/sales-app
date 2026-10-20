package com.salesapp.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.salesapp.core.AppConfig
import com.salesapp.core.KeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.json.Json

// Keeps the sign-in token and the signed-in user on the phone.
class SessionStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    var token: String? = store.get(TOKEN_KEY)
        private set

    fun loadUser(): User? = store.get(USER_KEY)?.let {
        try {
            json.decodeFromString(User.serializer(), it)
        } catch (e: Exception) {
            null
        }
    }

    fun save(token: String, user: User) {
        this.token = token
        store.put(TOKEN_KEY, token)
        saveUser(user)
    }

    fun saveUser(user: User) {
        store.put(USER_KEY, json.encodeToString(User.serializer(), user))
    }

    fun clear() {
        token = null
        store.put(TOKEN_KEY, null)
        store.put(USER_KEY, null)
    }

    private companion object {
        const val TOKEN_KEY = "token"
        const val USER_KEY = "user"
    }
}

// Everything the screens share: who is signed in, where we are, and messages.
class AppState(val store: KeyValueStore = KeyValueStore()) {
    val session = SessionStore(store)

    var user by mutableStateOf(session.loadUser().takeIf { session.token != null })
    var apiUrl by mutableStateOf(store.get(AppConfig.API_URL_KEY) ?: AppConfig.DEFAULT_API_URL)
        private set
    var userRows by mutableStateOf<List<UserRow>>(emptyList())
    var message by mutableStateOf<String?>(null)

    // Text shown in a red box on the login screen, e.g. after being signed out.
    var loginNotice by mutableStateOf<String?>(null)

    val nav = Navigator(if (user != null) Screen.Home else Screen.Phone)

    // Used for work that must outlive the screen, such as telling the server we signed out.
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var cachedApi: Api? = null
    private var cachedUrl: String? = null

    val api: Api
        get() {
            val existing = cachedApi
            if (existing != null && cachedUrl == apiUrl) return existing
            existing?.close()
            return Api(apiUrl) { session.token }.also {
                cachedApi = it
                cachedUrl = apiUrl
            }
        }

    var lastPhone: String
        get() = store.get(AppConfig.LAST_PHONE_KEY) ?: ""
        set(value) = store.put(AppConfig.LAST_PHONE_KEY, value)

    fun changeApiUrl(url: String?) {
        store.put(AppConfig.API_URL_KEY, url)
        apiUrl = url ?: AppConfig.DEFAULT_API_URL
    }

    fun signedIn(token: String, user: User) {
        session.save(token, user)
        this.user = user
        userRows = emptyList()
        message = null
        nav.replaceAll(Screen.Home)
    }

    fun signedOut(notice: String? = null) {
        session.clear()
        user = null
        userRows = emptyList()
        message = null
        loginNotice = notice
        nav.replaceAll(Screen.Phone)
    }
}
