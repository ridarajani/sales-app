package com.salesapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Burgundy, gold and warm white, the same colours as the prototype.
val Burgundy = Color(0xFF7A1F35)
val Gold = Color(0xFFB8892B)
val WarmWhite = Color(0xFFFBF8F3)
val Ink = Color(0xFF2A1A1F)

private val colors = lightColorScheme(
    primary = Burgundy,
    onPrimary = Color.White,
    secondary = Gold,
    onSecondary = Color.White,
    background = WarmWhite,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

@Composable
fun SalesTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = colors, content = content)
}
