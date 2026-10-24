package com.salesapp.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringWithContentsOfFile
import platform.Foundation.writeToFile

// The app's own Documents folder, which stays through app restarts.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class FileStore actual constructor() : Files {
    private fun path(name: String): String {
        val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).first() as String
        val dir = "$documents/outbox"
        NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
        return "$dir/$name"
    }

    override fun read(name: String): String? =
        NSString.stringWithContentsOfFile(path(name), NSUTF8StringEncoding, null)

    override fun write(name: String, text: String) {
        // atomically = true writes beside the real file and moves it over, so a half-written file
        // is never read back.
        NSString.create(string = text).writeToFile(path(name), true, NSUTF8StringEncoding, null)
    }

    override fun delete(name: String) {
        NSFileManager.defaultManager.removeItemAtPath(path(name), null)
    }
}
