package com.salesapp.core

actual object Platform {
    actual val isAndroid: Boolean = true

    @Suppress("DEPRECATION")
    private val info by lazy {
        val context = AppContext.context
        context.packageManager.getPackageInfo(context.packageName, 0)
    }

    actual val appVersionCode: Int get() = info.versionCode
    actual val appVersionName: String get() = info.versionName ?: ""
}
