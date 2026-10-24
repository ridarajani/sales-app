package com.salesapp.core

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreGraphics.CGSizeMake
import platform.UIKit.UIApplication
import platform.UIKit.UIGraphicsBeginImageContextWithOptions
import platform.UIKit.UIGraphicsEndImageContext
import platform.UIKit.UIGraphicsGetImageFromCurrentImageContext
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject

private const val CAMERA_MESSAGE = "The camera could not be opened."
private const val PHOTO_MESSAGE = "The photo could not be read. Try again."

// Opens the iPhone camera and shrinks what it took.
@OptIn(ExperimentalForeignApi::class)
actual class Camera actual constructor() {
    // Kept so iOS does not collect the picker or its delegate while the camera is open.
    private var keepAlive: Any? = null

    actual val available: Boolean
        get() = UIImagePickerController.isSourceTypeAvailable(UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera)

    actual fun take(onDone: (ByteArray?) -> Unit, onError: (String) -> Unit) {
        val top = topViewController()
        if (top == null || !available) {
            onError(CAMERA_MESSAGE)
            return
        }
        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypeCamera
        val delegate = PickerDelegate(
            onPicked = { image ->
                keepAlive = null
                val bytes = image?.let { shrink(it) }
                when {
                    image == null -> onDone(null)
                    bytes == null -> onError(PHOTO_MESSAGE)
                    else -> onDone(bytes)
                }
            },
        )
        picker.delegate = delegate
        keepAlive = delegate
        top.presentViewController(picker, true, null)
    }

    // Draws the picture again at about PHOTO_MAX_SIDE on its long side, then saves it as JPEG.
    private fun shrink(image: UIImage): ByteArray? {
        val width = image.size.useContents { width }
        val height = image.size.useContents { height }
        if (width <= 0.0 || height <= 0.0) return null
        val longest = maxOf(width, height)
        val factor = if (longest > PHOTO_MAX_SIDE) PHOTO_MAX_SIDE / longest else 1.0
        val target = CGSizeMake(width * factor, height * factor)
        UIGraphicsBeginImageContextWithOptions(target, true, 1.0)
        image.drawInRect(platform.CoreGraphics.CGRectMake(0.0, 0.0, width * factor, height * factor))
        val smaller = UIGraphicsGetImageFromCurrentImageContext()
        UIGraphicsEndImageContext()
        val jpeg = UIImageJPEGRepresentation(smaller ?: image, 0.8) ?: return null
        return jpeg.toByteArray()
    }

    private fun topViewController(): UIViewController? {
        val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene
        var top = (scene?.windows?.firstOrNull() as? UIWindow)?.rootViewController
            ?: UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }

    private class PickerDelegate(
        private val onPicked: (UIImage?) -> Unit,
    ) : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
        override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
            picker.dismissViewControllerAnimated(true, null)
            onPicked(didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage)
        }

        override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
            picker.dismissViewControllerAnimated(true, null)
            onPicked(null)
        }
    }
}
