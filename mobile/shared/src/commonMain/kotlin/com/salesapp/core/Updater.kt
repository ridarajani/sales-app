package com.salesapp.core

// Downloads a new version of the app and opens the installer. Android only.
expect class Updater() {
    fun downloadAndInstall(
        url: String,
        versionName: String,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    )
}
