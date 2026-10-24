package com.salesapp.core

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentInteractionController
import platform.UIKit.UIDocumentInteractionControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.UIWindowScene
import platform.darwin.NSObject

// On iPhone both "Print or save" and "Share on WhatsApp" go through iOS's own sheets: the document
// viewer (which has Print and Save to Files in it) and the share sheet.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
actual class Documents actual constructor() {
    // Kept so iOS does not collect the controller while its sheet is open.
    private var keepAlive: Any? = null

    actual suspend fun open(fileName: String, bytes: ByteArray, contentType: String, share: Boolean): Boolean {
        val path = NSTemporaryDirectory() + fileName
        val data = bytes.usePinned { pinned ->
            NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
        }
        if (!data.writeToFile(path, true)) return false
        val url = NSURL.fileURLWithPath(path)
        val top = topViewController() ?: return false
        if (share) {
            val sheet = UIActivityViewController(listOf(url), null)
            keepAlive = sheet
            top.presentViewController(sheet, true, null)
        } else {
            val viewer = UIDocumentInteractionController.interactionControllerWithURL(url)
            val delegate = ViewerDelegate(top)
            viewer.delegate = delegate
            keepAlive = delegate
            if (!viewer.presentPreviewAnimated(true)) {
                keepAlive = null
                return false
            }
        }
        return true
    }

    private fun topViewController(): UIViewController? {
        val scene = UIApplication.sharedApplication.connectedScenes.firstOrNull() as? UIWindowScene
        var top = (scene?.windows?.firstOrNull() as? platform.UIKit.UIWindow)?.rootViewController
            ?: UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) top = top.presentedViewController
        return top
    }

    private class ViewerDelegate(private val host: UIViewController) : NSObject(), UIDocumentInteractionControllerDelegateProtocol {
        override fun documentInteractionControllerViewControllerForPreview(controller: UIDocumentInteractionController): UIViewController = host
    }
}
