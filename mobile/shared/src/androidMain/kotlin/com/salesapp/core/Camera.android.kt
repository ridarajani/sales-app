package com.salesapp.core

import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import android.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val CAMERA_MESSAGE = "The camera could not be opened."
private const val PHOTO_MESSAGE = "The photo could not be read. Try again."

// Opens the phone's own camera app, then shrinks what it took.
actual class Camera actual constructor() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    actual val available: Boolean
        get() = AppContext.context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    actual fun take(onDone: (ByteArray?) -> Unit, onError: (String) -> Unit) {
        val activity = AppContext.activity
        if (activity == null) {
            onError(CAMERA_MESSAGE)
            return
        }
        val context = AppContext.context
        val dir = File(context.cacheDir, "photos").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "delivery-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        // A one-off registration: it is launched straight away and taken off once the answer comes.
        var launcher: androidx.activity.result.ActivityResultLauncher<android.net.Uri>? = null
        launcher = activity.activityResultRegistry.register(
            "delivery-photo-${System.currentTimeMillis()}",
            ActivityResultContracts.TakePicture(),
        ) { taken ->
            launcher?.unregister()
            if (!taken) {
                file.delete()
                onDone(null)
                return@register
            }
            scope.launch {
                try {
                    val bytes = withContext(Dispatchers.IO) { shrink(file) }
                    file.delete()
                    if (bytes == null) onError(PHOTO_MESSAGE) else onDone(bytes)
                } catch (e: Exception) {
                    file.delete()
                    onError(PHOTO_MESSAGE)
                }
            }
        }
        try {
            launcher.launch(uri)
        } catch (e: Exception) {
            launcher.unregister()
            file.delete()
            onError(CAMERA_MESSAGE)
        }
    }

    // Reads the photo at a size close to what is wanted, turns it the right way up, then saves it as JPEG.
    private fun shrink(file: File): ByteArray? {
        val size = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, size)
        if (size.outWidth <= 0 || size.outHeight <= 0) return null
        var sample = 1
        while (maxOf(size.outWidth, size.outHeight) / (sample * 2) >= PHOTO_MAX_SIDE) sample *= 2
        val loaded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        val longest = maxOf(loaded.width, loaded.height)
        val scaled = if (longest > PHOTO_MAX_SIDE) {
            val factor = PHOTO_MAX_SIDE.toFloat() / longest
            Bitmap.createScaledBitmap(loaded, (loaded.width * factor).roundToInt(), (loaded.height * factor).roundToInt(), true)
        } else {
            loaded
        }
        val turned = turnUpright(file, scaled)
        return ByteArrayOutputStream().use { out ->
            turned.compress(Bitmap.CompressFormat.JPEG, 80, out)
            if (turned !== loaded) turned.recycle()
            if (scaled !== loaded && scaled !== turned) scaled.recycle()
            loaded.recycle()
            out.toByteArray()
        }
    }

    // Phones often save the picture sideways and only note the turn in the file.
    private fun turnUpright(file: File, bitmap: Bitmap): Bitmap {
        val degrees = when (ExifInterface(file.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
            else -> return bitmap
        }
        val matrix = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }
}
