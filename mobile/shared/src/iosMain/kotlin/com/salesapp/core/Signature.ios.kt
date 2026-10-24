package com.salesapp.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import platform.CoreGraphics.CGSizeMake
import platform.Foundation.NSData
import platform.UIKit.UIBezierPath
import platform.UIKit.UIColor
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImagePNGRepresentation
import platform.CoreGraphics.CGPointMake
import platform.CoreGraphics.CGLineCap
import platform.CoreGraphics.CGLineJoin

// Draws the strokes on a white picture and returns it as a PNG.
@OptIn(ExperimentalForeignApi::class)
actual fun signaturePng(strokes: List<List<Pair<Float, Float>>>, width: Int, height: Int): ByteArray? {
    if (strokes.all { it.size < 2 } || width <= 0 || height <= 0) return null
    UIGraphicsBeginImageContextWithOptions(CGSizeMake(width.toDouble(), height.toDouble()), true, 1.0)
    UIColor.whiteColor.setFill()
    UIBezierPath.bezierPathWithRect(platform.CoreGraphics.CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble())).fill()
    UIColor.blackColor.setStroke()
    strokes.filter { it.size >= 2 }.forEach { stroke ->
        val path = UIBezierPath()
        path.lineWidth = height / 48.0
        path.lineCapStyle = CGLineCap.kCGLineCapRound
        path.lineJoinStyle = CGLineJoin.kCGLineJoinRound
        path.moveToPoint(CGPointMake(stroke[0].first.toDouble(), stroke[0].second.toDouble()))
        stroke.drop(1).forEach { (x, y) -> path.addLineToPoint(CGPointMake(x.toDouble(), y.toDouble())) }
        path.stroke()
    }
    val image = UIGraphicsGetImageFromCurrentImageContext()
    UIGraphicsEndImageContext()
    val png = image?.let { UIImagePNGRepresentation(it) } ?: return null
    return png.toByteArray()
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray = bytes?.readBytes(length.toInt()) ?: ByteArray(0)
