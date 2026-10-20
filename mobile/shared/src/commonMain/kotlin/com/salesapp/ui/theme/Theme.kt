package com.salesapp.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight

// Burgundy, gold and warm white, the same colours as the prototype.
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

private val colors = lightColorScheme(
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

@Composable
fun SalesTheme(content: @Composable () -> Unit) {
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
