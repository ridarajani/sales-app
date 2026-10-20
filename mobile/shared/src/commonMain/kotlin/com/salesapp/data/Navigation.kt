package com.salesapp.data

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Every screen in the app.
sealed interface Screen {
    data object Phone : Screen
    data class Pin(val phone: String, val name: String) : Screen
    data object Home : Screen
    data object Users : Screen
    data object AddUser : Screen
    data class UserDetail(val id: Int) : Screen
    data class ResetPin(val id: Int) : Screen
    data object ServerAddress : Screen
}

// Remembers which screens were opened so Back can return to them.
class Navigator(start: Screen) {
    var stack by mutableStateOf(listOf(start))
        private set

    val current: Screen get() = stack.last()
    val canGoBack: Boolean get() = stack.size > 1

    fun push(screen: Screen) {
        stack = stack + screen
    }

    fun pop(): Boolean {
        if (!canGoBack) return false
        stack = stack.dropLast(1)
        return true
    }

    fun replaceAll(screen: Screen) {
        stack = listOf(screen)
    }
}
