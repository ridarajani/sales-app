package com.salesapp.core

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import java.io.ByteArrayOutputStream

// Draws the strokes on a white bitmap and returns it as a PNG, which is what the server stores.
actual fun signaturePng(strokes: List<List<Pair<Float, Float>>>, width: Int, height: Int): ByteArray? {
    if (strokes.all { it.size < 2 } || width <= 0 || height <= 0) return null
    val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.drawColor(Color.WHITE)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeWidth = height / 48f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    strokes.filter { it.size >= 2 }.forEach { stroke ->
        val path = Path()
        path.moveTo(stroke[0].first, stroke[0].second)
        stroke.drop(1).forEach { (x, y) -> path.lineTo(x, y) }
        canvas.drawPath(path, paint)
    }
    return ByteArrayOutputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        bitmap.recycle()
        out.toByteArray()
    }
}
