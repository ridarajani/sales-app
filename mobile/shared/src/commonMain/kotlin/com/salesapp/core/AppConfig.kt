package com.salesapp.core

// The address of the server that the app talks to.
expect fun defaultApiUrl(): String

object AppConfig {
    const val API_URL_KEY = "api_url"
    const val LAST_PHONE_KEY = "last_phone"

    val DEFAULT_API_URL: String get() = defaultApiUrl()
}
