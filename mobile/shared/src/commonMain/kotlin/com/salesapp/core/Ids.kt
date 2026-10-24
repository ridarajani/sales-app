package com.salesapp.core

import kotlin.random.Random

// A random id the phone makes once per form, so that sending the same payment or expense twice
// after a lost answer does not record it twice. Version 4, the same shape the server expects.
fun randomUuid(): String {
    val bytes = Random.nextBytes(16)
    // Version 4 and the standard variant, so the id looks like any other UUID.
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte()
    val hex = bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }
    return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20)}"
}
