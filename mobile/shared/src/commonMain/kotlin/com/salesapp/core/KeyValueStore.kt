package com.salesapp.core

// A tiny place on the phone to remember short text between app starts.
expect class KeyValueStore() {
    fun get(key: String): String?
    fun put(key: String, value: String?)
}
