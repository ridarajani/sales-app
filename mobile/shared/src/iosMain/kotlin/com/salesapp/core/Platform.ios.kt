package com.salesapp.core

import kotlin.experimental.ExperimentalNativeApi
import platform.Foundation.NSBundle

actual object Platform {
    actual val isAndroid: Boolean = false

    actual val appVersionCode: Int
        get() = (NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleVersion") as? String)?.toIntOrNull() ?: 0

    actual val appVersionName: String
        get() = NSBundle.mainBundle.objectForInfoDictionaryKey("CFBundleShortVersionString") as? String ?: ""

    // Xcode's Debug configuration builds a debug framework; Release builds a release one.
    @OptIn(ExperimentalNativeApi::class)
    actual val isDebugBuild: Boolean = kotlin.native.Platform.isDebugBinary
}
