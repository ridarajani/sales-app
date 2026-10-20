package com.salesapp.core

// Facts about the phone the app runs on.
expect object Platform {
    val isAndroid: Boolean
    val appVersionCode: Int
    val appVersionName: String
}
