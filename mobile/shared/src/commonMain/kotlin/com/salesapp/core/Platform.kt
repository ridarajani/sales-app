package com.salesapp.core

// Facts about the phone the app runs on.
expect object Platform {
    val isAndroid: Boolean
    val appVersionCode: Int
    val appVersionName: String

    // A build made for testing, which may still talk to a server over plain http.
    val isDebugBuild: Boolean
}
