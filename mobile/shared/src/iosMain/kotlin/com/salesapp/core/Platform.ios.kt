package com.salesapp.core

import platform.Foundation.NSBundle

actual object Platform {
    actual val isAndroid: Boolean = false

    actual val appVersionCode: Int
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 0

    actual val appVersionName: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""
}
