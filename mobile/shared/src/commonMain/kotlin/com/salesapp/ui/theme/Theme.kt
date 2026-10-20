package com.salesapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Brand colours, the same as the prototype: burgundy, gold and warm white.
val Burgundy = Color(0xFF7A1F35)
val Gold = Color(0xFFB8892B)
val WarmWhite = Color(0xFFFBF8F3)
val Ink = Color(0xFF2A1A1F)

private val LightColors = lightColorScheme(
    primary = Burgundy,
    onPrimary = Color.White,
    secondary = Gold,
    onSecondary = Color.White,
    background = WarmWhite,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFE58AA0),
    onPrimary = Color(0xFF4A0F1F),
    secondary = Color(0xFFE2B85E),
    onSecondary = Color(0xFF3A2A06),
    background = Color(0xFF1C1316),
    onBackground = Color(0xFFF3E9E6),
    surface = Color(0xFF271B1F),
    onSurface = Color(0xFFF3E9E6),
)

/**
 * The phone's own colour scheme (Material You on Android 12 and later), or null where the
 * platform has none, in which case the brand colours are used.
 */
@Composable
internal expect fun platformColorScheme(darkTheme: Boolean): ColorScheme?

/**
 * App theme. Follows the phone's light or dark setting. With [dynamicColor] on, Android 12+
 * phones use their wallpaper colours instead of burgundy and gold; it is off by default to
 * keep the brand look.
 */
@Composable
fun SalesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = (if (dynamicColor) platformColorScheme(darkTheme) else null)
        ?: if (darkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
