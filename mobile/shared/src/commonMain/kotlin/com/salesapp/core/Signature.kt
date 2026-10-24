package com.salesapp.core

// A signature drawn on the phone, as the strokes the finger made. Each stroke is the points it
// went through, in the pad's own pixels. Turned into a PNG by the platform, because Compose
// cannot read its own canvas back.
expect fun signaturePng(strokes: List<List<Pair<Float, Float>>>, width: Int, height: Int): ByteArray?
