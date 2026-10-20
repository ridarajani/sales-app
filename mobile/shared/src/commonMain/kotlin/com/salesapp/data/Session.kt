package com.salesapp.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.salesapp.core.AppConfig
import com.salesapp.core.KeyValueStore
import com.salesapp.resources.Res
import com.salesapp.resources.err_generic
import com.salesapp.resources.err_signed_out
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.getString

// Keeps the sign-in token and the signed-in user on the phone.
class SessionStore(private val store: KeyValueStore) {
    private val json = Json { ignoreUnknownKeys = true }

    var token: String? = store.get(TOKEN_KEY)
        private set

    private val signedInUser = MutableStateFlow<User?>(null)

    // The signed-in user, for view models. Null when signed out.
    val user: StateFlow<User?> = signedInUser.asStateFlow()

    init {
        if (token != null) signedInUser.value = loadUser()
    }

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
        signedInUser.value = user
    }

    fun clear() {
        token = null
        store.put(TOKEN_KEY, null)
        store.put(USER_KEY, null)
        signedInUser.value = null
    }

    // Forget the sign-in on the phone.
    fun onSignedOut() = clear()

    private companion object {
        const val TOKEN_KEY = "token"
        const val USER_KEY = "user"
    }
}

// Everything the screens share: who is signed in, where we are, and messages.
class AppState(val store: KeyValueStore, val session: SessionStore) {

    var user by mutableStateOf(session.loadUser().takeIf { session.token != null })
    var apiUrl by mutableStateOf(store.get(AppConfig.API_URL_KEY) ?: AppConfig.DEFAULT_API_URL)
        private set
    var userRows by mutableStateOf<List<UserRow>>(emptyList())
    var message by mutableStateOf<String?>(null)

    // Text shown in a red box on the login screen, e.g. after being signed out.
    var loginNotice by mutableStateOf<String?>(null)

    // True while the lists area can still go back inside itself (it handles Back, not the app).
    var listsCanPop by mutableStateOf(false)

    val nav = Navigator(if (user != null) Screen.Home else Screen.Phone)

    // Used for work that must outlive the screen, such as telling the server we signed out.
    val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // One Api for the whole app. It reads the address and the token each time it is used.
    val api = Api({ apiUrl }) { session.token }

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
        session.onSignedOut()
        user = null
        userRows = emptyList()
        message = null
        loginNotice = notice
        nav.replaceAll(Screen.Phone)
    }

    // Sign out if the server says the sign-in is no longer good; otherwise return the message to show.
    suspend fun handleApiError(e: Throwable): String {
        if (e is ApiException && e.isSignedOut) {
            signedOut(getString(Res.string.err_signed_out))
            return e.message
        }
        return e.message ?: getString(Res.string.err_generic)
    }
}
