package com.salesapp.ui.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ShoppingCart
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

// Icons the tiles use. Person, list, settings and cart come from Material; the rest are simple outlines drawn here.
object AppIcons {
    val Person: ImageVector get() = Icons.Outlined.Person
    val List: ImageVector get() = Icons.AutoMirrored.Outlined.List
    val Settings: ImageVector get() = Icons.Outlined.Settings
    val Cart: ImageVector get() = Icons.Outlined.ShoppingCart

    val Chart: ImageVector by lazy {
        outline("Chart") {
            moveTo(4f, 20f); lineTo(20f, 20f)
            moveTo(7f, 16f); lineTo(7f, 11f)
            moveTo(12f, 16f); lineTo(12f, 5f)
            moveTo(17f, 16f); lineTo(17f, 9f)
        }
    }
    val Grid: ImageVector by lazy {
        outline("Grid") {
            rect(4f, 4f, 10f, 10f); rect(14f, 4f, 20f, 10f)
            rect(4f, 14f, 10f, 20f); rect(14f, 14f, 20f, 20f)
        }
    }
    val Truck: ImageVector by lazy {
        outline("Truck") {
            rect(2f, 6f, 14f, 16f)
            moveTo(14f, 9f); lineTo(18f, 9f); lineTo(21f, 12f); lineTo(21f, 16f); lineTo(14f, 16f)
            circle(7f, 18f, 2f); circle(17f, 18f, 2f)
        }
    }
    val Money: ImageVector by lazy {
        outline("Money") {
            rect(3f, 6f, 21f, 18f)
            circle(12f, 12f, 3f)
        }
    }
    val Box: ImageVector by lazy {
        outline("Box") {
            moveTo(12f, 3f); lineTo(20f, 7f); lineTo(20f, 17f); lineTo(12f, 21f); lineTo(4f, 17f); lineTo(4f, 7f); close()
            moveTo(4f, 7f); lineTo(12f, 11f); lineTo(20f, 7f)
            moveTo(12f, 11f); lineTo(12f, 21f)
        }
    }
    val Inbox: ImageVector by lazy {
        outline("Inbox") {
            moveTo(3f, 13f); lineTo(6f, 5f); lineTo(18f, 5f); lineTo(21f, 13f); lineTo(21f, 19f); lineTo(3f, 19f); close()
            moveTo(3f, 13f); lineTo(8f, 13f); lineTo(9f, 16f); lineTo(15f, 16f); lineTo(16f, 13f); lineTo(21f, 13f)
        }
    }
    val Receipt: ImageVector by lazy {
        outline("Receipt") {
            moveTo(5f, 3f); lineTo(19f, 3f); lineTo(19f, 21f); lineTo(16f, 19f); lineTo(12f, 21f); lineTo(8f, 19f); lineTo(5f, 21f); close()
            moveTo(9f, 8f); lineTo(15f, 8f)
            moveTo(9f, 12f); lineTo(15f, 12f)
        }
    }
    val People: ImageVector by lazy {
        outline("People") {
            circle(9f, 8f, 3f)
            moveTo(3f, 20f); lineTo(3f, 18f); curveTo(3f, 15f, 6f, 14f, 9f, 14f); curveTo(12f, 14f, 15f, 15f, 15f, 18f); lineTo(15f, 20f)
            moveTo(16f, 5.5f); curveTo(18.5f, 5.5f, 19f, 9f, 17f, 10.5f)
            moveTo(18f, 14.3f); curveTo(20f, 14.8f, 21f, 16f, 21f, 18f); lineTo(21f, 20f)
        }
    }
}

private fun PathBuilder.rect(l: Float, t: Float, r: Float, b: Float) {
    moveTo(l, t); lineTo(r, t); lineTo(r, b); lineTo(l, b); close()
}

private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
    moveTo(cx - r, cy)
    arcTo(r, r, 0f, true, true, cx + r, cy)
    arcTo(r, r, 0f, true, true, cx - r, cy)
    close()
}

private fun outline(name: String, draw: PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .path(
            stroke = SolidColor(Color.Black),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            pathBuilder = draw,
        )
        .build()
