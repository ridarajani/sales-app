package com.salesapp.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.salesapp.ui.theme.sales

// Where the customer signs with a finger. The finished strokes live in the screen's state, so the
// delivery can be sent with them; the stroke being drawn right now is kept here so it follows the
// finger without going through the view model.
@Composable
fun SignaturePad(
    strokes: List<List<Pair<Float, Float>>>,
    onStrokeDone: (List<Pair<Float, Float>>) -> Unit,
    onSize: (IntSize) -> Unit,
    hint: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: androidx.compose.ui.unit.Dp = 180.dp,
) {
    val scheme = MaterialTheme.colorScheme
    val ink = MaterialTheme.sales.bad
    val shape = RoundedCornerShape(12.dp)
    val current = remember { mutableStateListOf<Offset>() }
    var padSize by remember { mutableStateOf(IntSize.Zero) }
    // Clear empties the screen's strokes; the half-drawn one goes with them.
    LaunchedEffect(strokes.isEmpty()) { if (strokes.isEmpty()) current.clear() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(shape)
            .background(scheme.surface)
            .border(1.dp, scheme.outline, shape),
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectDragGestures(
                        onDragStart = { start ->
                            current.clear()
                            current.add(start)
                        },
                        onDragEnd = {
                            if (current.size >= 2) onStrokeDone(current.map { it.x to it.y })
                            current.clear()
                        },
                        onDragCancel = { current.clear() },
                    ) { change, _ ->
                        change.consume()
                        current.add(change.position)
                    }
                },
        ) {
            if (padSize.width != size.width.toInt() || padSize.height != size.height.toInt()) {
                padSize = IntSize(size.width.toInt(), size.height.toInt())
                onSize(padSize)
            }
            val line = Stroke(width = size.height / 48f, cap = StrokeCap.Round, join = StrokeJoin.Round)
            (strokes.map { stroke -> stroke.map { Offset(it.first, it.second) } } + listOf(current.toList()))
                .filter { it.size >= 2 }
                .forEach { points ->
                    val path = Path().apply {
                        moveTo(points[0].x, points[0].y)
                        points.drop(1).forEach { lineTo(it.x, it.y) }
                    }
                    drawPath(path, color = ink, style = line)
                }
        }
        if (strokes.isEmpty() && current.isEmpty()) {
            Text(
                hint,
                color = scheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.align(Alignment.Center).padding(horizontal = 16.dp),
            )
        }
    }
}
