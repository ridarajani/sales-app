package com.salesapp.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

// Where the user screens get their data. Keeps the last list so a person can be shown without loading again.
class UsersRepository(private val api: Api, app: AppState) {
    private val _rows = MutableStateFlow<List<UserRow>>(emptyList())
    val rows: StateFlow<List<UserRow>> = _rows.asStateFlow()

    init {
        app.onSessionChanged { clear() }
    }

    fun clear() {
        _rows.value = emptyList()
    }

    suspend fun refresh(): List<UserRow> {
        val list = api.users()
        _rows.value = list
        return list
    }

    fun cached(id: Int): UserRow? = _rows.value.firstOrNull { it.id == id }

    // The server has no call for one person, so reload the list when the person is not kept yet.
    suspend fun user(id: Int): UserRow? = cached(id) ?: refresh().firstOrNull { it.id == id }

    suspend fun createUser(request: NewUserRequest): UserRow {
        val added = api.createUser(request)
        _rows.update { it + added }
        return added
    }

    // Client, 24 Oct: only the fields sent are changed.
    suspend fun updateUser(id: Int, request: UserUpdateRequest): UserRow = replace(api.updateUser(id, request))

    // A user nothing refers to goes; one with history keeps their name on old documents.
    suspend fun deleteUser(id: Int): DeleteResult {
        val result = api.deleteUser(id)
        _rows.update { rows -> rows.filterNot { it.id == id } }
        return result
    }

    suspend fun switchOff(id: Int): UserRow = replace(api.switchOff(id))

    suspend fun switchOn(id: Int): UserRow = replace(api.switchOn(id))

    suspend fun resetPin(id: Int, pin: String) = api.resetPin(id, pin)

    private fun replace(updated: UserRow): UserRow {
        _rows.update { rows -> rows.map { if (it.id == updated.id) updated else it } }
        return updated
    }
}
