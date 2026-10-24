package com.salesapp.core

// Hands a file the server sent to the phone: "Print or save" opens it in the phone's own viewer,
// "Share on WhatsApp" opens the share sheet. The file is written to the app's own cache first.
expect class Documents() {
    // Writes the bytes to a cache file and opens it. share = the share sheet, otherwise a viewer.
    suspend fun open(fileName: String, bytes: ByteArray, contentType: String, share: Boolean): Boolean
}
