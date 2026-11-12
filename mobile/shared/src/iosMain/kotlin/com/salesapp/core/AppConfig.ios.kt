package com.salesapp.core

import platform.Foundation.NSBundle

// A release build talks to the live server named in Local.xcconfig (SALES_API_HOST), always over
// https. A debug build talks to the Mac: the iPhone simulator shares the Mac's network.
actual fun defaultApiUrl(): String {
    if (Platform.isDebugBuild) return "http://localhost:3000"
    val host = (NSBundle.mainBundle.objectForInfoDictionaryKey("SalesApiHost") as? String)?.trim().orEmpty()
    return if (host.isEmpty() || host.startsWith("$")) "http://localhost:3000" else "https://$host"
}
