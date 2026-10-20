package com.salesapp.core

import android.content.Intent
import androidx.core.content.FileProvider
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.contentLength
import io.ktor.http.isSuccess
import io.ktor.utils.io.readAvailable
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val DOWNLOAD_MESSAGE = "Could not download the new version. Try again later."

private class DownloadFailed : Exception(DOWNLOAD_MESSAGE)

actual class Updater actual constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    actual fun downloadAndInstall(
        url: String,
        versionName: String,
        onProgress: (Float) -> Unit,
        onDone: () -> Unit,
        onError: (String) -> Unit,
    ) {
        scope.launch {
            try {
                val context = AppContext.context
                val file = withContext(Dispatchers.IO) {
                    val dir = File(context.cacheDir, "updates").apply { mkdirs() }
                    dir.listFiles()?.forEach { it.delete() }
                    val target = File(dir, "sales-app-$versionName.apk")
                    try {
                        download(url, target) { value -> launch(Dispatchers.Main) { onProgress(value) } }
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                    target
                }
                val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(uri, "application/vnd.android.package-archive")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(intent)
                onDone()
            } catch (e: CancellationException) {
                throw e
            } catch (e: DownloadFailed) {
                onError(e.message ?: DOWNLOAD_MESSAGE)
            } catch (e: Exception) {
                onError(DOWNLOAD_MESSAGE)
            }
        }
    }

    // Saves the file piece by piece so a big file never sits in memory.
    private suspend fun download(url: String, target: File, onProgress: (Float) -> Unit) {
        val client = HttpClient(OkHttp) {
            install(HttpTimeout) {
                requestTimeoutMillis = 10 * 60 * 1000
                connectTimeoutMillis = 15_000
            }
        }
        try {
            client.prepareGet(url).execute { response ->
                if (!response.status.isSuccess()) throw DownloadFailed()
                val total = response.contentLength() ?: 0L
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(16 * 1024)
                var done = 0L
                var lastPercent = -1
                target.outputStream().use { out ->
                    while (true) {
                        val count = channel.readAvailable(buffer, 0, buffer.size)
                        if (count < 0) break
                        if (count == 0) continue
                        out.write(buffer, 0, count)
                        done += count
                        if (total > 0) {
                            val percent = (done * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent / 100f)
                            }
                        }
                    }
                }
            }
        } finally {
            client.close()
        }
    }
}
