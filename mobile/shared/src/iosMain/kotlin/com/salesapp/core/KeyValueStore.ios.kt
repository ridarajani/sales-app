package com.salesapp.core

import platform.Foundation.NSUserDefaults

actual class KeyValueStore actual constructor() {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual fun get(key: String): String? = defaults.stringForKey(key)

    actual fun put(key: String, value: String?) {
        if (value == null) defaults.removeObjectForKey(key) else defaults.setObject(value, forKey = key)
    }
}
