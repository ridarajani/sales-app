package com.salesapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Brand colours, the same as the prototype: burgundy, gold and warm white.
val Burgundy = Color(0xFF7A1F35)
val Gold = Color(0xFFB8892B)
val WarmWhite = Color(0xFFFBF8F3)
val Ink = Color(0xFF2A1A1F)
val Background = Color(0xFFFBF7F2)
val Surface2 = Color(0xFFF4ECE4)
val Line = Color(0xFFE6D8CC)
val Muted = Color(0xFF6F5A5E)
val AccentSoft = Color(0xFFF6E3E8)
val GoldSoft = Color(0xFFF7EDD5)
val Bad = Color(0xFFB3261E)
val BadSoft = Color(0xFFFBE2DF)
val Good = Color(0xFF1D7536)
val GoodSoft = Color(0xFFDCF1E1)

// Each company has its own colour.
fun companyColor(code: String): Color = when (code) {
    "LV" -> Color(0xFFB8892B)
    "ZT" -> Color(0xFF8A4FA3)
    "ZF" -> Color(0xFF2F62A8)
    "GL" -> Color(0xFFC25A2E)
    else -> Muted
}

private val LightColors = lightColorScheme(
    primary = Burgundy,
    onPrimary = Color.White,
    secondary = Gold,
    onSecondary = Color.White,
    background = Background,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    error = Bad,
    onError = Color.White,
    outline = Line,
    surfaceVariant = Surface2,
    onSurfaceVariant = Muted,
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
 * App theme. With [dynamicColor] on, Android 12+ phones use their wallpaper colours instead of
 * burgundy and gold; it is off by default to keep the brand look. Dark mode is ready in the
 * theme but stays off until the screens take all their colours from colorScheme (Phase 1
 * screens use the light brand colours directly).
 */
@Composable
fun SalesTheme(
    darkTheme: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = (if (dynamicColor) platformColorScheme(darkTheme) else null)
        ?: if (darkTheme) DarkColors else LightColors
    val base = Typography()
    val typography = base.copy(
        headlineLarge = base.headlineLarge.copy(fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
    )
    MaterialTheme(colorScheme = colors, typography = typography, content = content)
}
