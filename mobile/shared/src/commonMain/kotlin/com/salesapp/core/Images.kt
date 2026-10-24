package com.salesapp.core

import androidx.compose.ui.graphics.ImageBitmap

// Turns the bytes of a PNG or JPEG the server sent into something Compose can draw.
expect fun decodeImage(bytes: ByteArray): ImageBitmap?
