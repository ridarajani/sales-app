package com.salesapp.core

private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

// Bytes as base64 text, which is how the server takes a signature or a photo.
fun ByteArray.toBase64(): String {
    val out = StringBuilder((size + 2) / 3 * 4)
    var i = 0
    while (i + 2 < size) {
        val n = (this[i].toInt() and 0xFF shl 16) or (this[i + 1].toInt() and 0xFF shl 8) or (this[i + 2].toInt() and 0xFF)
        out.append(ALPHABET[n ushr 18 and 0x3F]).append(ALPHABET[n ushr 12 and 0x3F])
        out.append(ALPHABET[n ushr 6 and 0x3F]).append(ALPHABET[n and 0x3F])
        i += 3
    }
    when (size - i) {
        1 -> {
            val n = this[i].toInt() and 0xFF shl 16
            out.append(ALPHABET[n ushr 18 and 0x3F]).append(ALPHABET[n ushr 12 and 0x3F]).append("==")
        }
        2 -> {
            val n = (this[i].toInt() and 0xFF shl 16) or (this[i + 1].toInt() and 0xFF shl 8)
            out.append(ALPHABET[n ushr 18 and 0x3F]).append(ALPHABET[n ushr 12 and 0x3F])
            out.append(ALPHABET[n ushr 6 and 0x3F]).append('=')
        }
    }
    return out.toString()
}
