package com.salesapp.core

// Takes a photo with the phone's camera for a delivery. The picture is shrunk to about 1600px on
// its long side and returned as JPEG bytes, so it is small enough to send.
expect class Camera() {
    val available: Boolean

    // onDone is called with the JPEG bytes, or with null when the person backed out.
    fun take(onDone: (ByteArray?) -> Unit, onError: (String) -> Unit)
}

const val PHOTO_MAX_SIDE: Int = 1600
