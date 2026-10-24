package com.salesapp.core

import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// Writes the file into the app's cache and hands it to another app through a FileProvider, so no
// storage permission is needed.
actual class Documents actual constructor() {
    actual suspend fun open(fileName: String, bytes: ByteArray, contentType: String, share: Boolean): Boolean {
        val context = AppContext.context
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "docs").apply { mkdirs() }
            // Only the newest few are kept; these are only here to be handed on.
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.drop(8)?.forEach { it.delete() }
            File(dir, fileName).apply { writeBytes(bytes) }
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = if (share) {
            Intent.createChooser(
                Intent(Intent.ACTION_SEND).apply {
                    type = contentType
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                },
                null,
            )
        } else {
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, contentType)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            true
        } catch (e: android.content.ActivityNotFoundException) {
            // No app on the phone can open a PDF.
            false
        }
    }
}
