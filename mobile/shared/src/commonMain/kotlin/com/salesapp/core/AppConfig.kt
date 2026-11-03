package com.salesapp.core

// The address of the server that the app talks to.
expect fun defaultApiUrl(): String

object AppConfig {
    const val API_URL_KEY = "api_url"
    const val LAST_PHONE_KEY = "last_phone"

    val DEFAULT_API_URL: String get() = defaultApiUrl()
}

// The address to use: the one saved on the phone, unless a release build finds a plain http one
// there (left from testing), which it ignores so PINs and signatures never travel unencrypted.
fun usableApiUrl(saved: String?, default: String, debugBuild: Boolean): String = when {
    saved.isNullOrBlank() -> default
    !debugBuild && !saved.startsWith("https://") -> default
    else -> saved
}

// Whether a typed server address may be saved: release builds take https only.
fun serverAddressAllowed(url: String, debugBuild: Boolean): Boolean =
    url.startsWith("https://") || (debugBuild && url.startsWith("http://"))

