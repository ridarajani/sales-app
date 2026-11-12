package com.salesapp.core

// Comes from the app's build: the live server for a release build, the Mac (seen from the
// Android emulator at 10.0.2.2) for a debug build.
actual fun defaultApiUrl(): String = AppContext.apiUrl
