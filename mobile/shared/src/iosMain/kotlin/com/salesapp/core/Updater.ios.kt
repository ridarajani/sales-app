package com.salesapp.core

// iPhone updates come through TestFlight, so there is nothing to do here.
actual class Updater actual constructor() {
    actual fun downloadAndInstall(
        url: String,
        versionName: String,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) = Unit
}
