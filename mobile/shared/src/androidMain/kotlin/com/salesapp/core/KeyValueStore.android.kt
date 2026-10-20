package com.salesapp.core

import android.content.Context

actual class KeyValueStore actual constructor() {
    private val prefs = AppContext.context.getSharedPreferences("salesapp", Context.MODE_PRIVATE)

    actual fun get(key: String): String? = prefs.getString(key, null)

    actual fun put(key: String, value: String?) {
        prefs.edit().apply {
            if (value == null) remove(key) else putString(key, value)
        }.apply()
    }
}
