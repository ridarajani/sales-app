package com.salesapp.core

// The iPhone simulator shares the Mac's network.
actual fun defaultApiUrl(): String = "http://localhost:3000"
