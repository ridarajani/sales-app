package com.salesapp.core

import java.io.File

// The app's own files folder, which no other app can read.
actual class FileStore actual constructor() : Files {
    private fun file(name: String): File {
        val dir = File(AppContext.context.filesDir, "outbox").apply { mkdirs() }
        return File(dir, name)
    }

    override fun read(name: String): String? = try {
        file(name).takeIf { it.exists() }?.readText()
    } catch (e: Exception) {
        null
    }

    override fun write(name: String, text: String) {
        // Written beside the real file and moved over it, so a half-written file is never read back.
        val target = file(name)
        val temp = File(target.parentFile, "${name}.tmp")
        temp.writeText(text)
        if (!temp.renameTo(target)) {
            target.writeText(text)
            temp.delete()
        }
    }

    override fun delete(name: String) {
        try {
            file(name).delete()
        } catch (e: Exception) {
            // Nothing to do: it is already gone.
        }
    }
}
