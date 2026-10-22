package com.salesapp.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

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

// Brand colours that Material's colour scheme has no slot for, with a light and a dark set.
data class SalesColors(
    val line: Color,
    val muted: Color,
    val accentSoft: Color,
    val goldSoft: Color,
    val gold: Color,
    val good: Color,
    val goodSoft: Color,
    val bad: Color,
    val badSoft: Color,
    val surface2: Color,
    val companyLV: Color,
    val companyZT: Color,
    val companyZF: Color,
    val companyGL: Color,
)

val LightSalesColors = SalesColors(
    line = Line,
    muted = Muted,
    accentSoft = AccentSoft,
    goldSoft = GoldSoft,
    gold = Gold,
    good = Good,
    goodSoft = GoodSoft,
    bad = Bad,
    badSoft = BadSoft,
    surface2 = Surface2,
    companyLV = Color(0xFFB8892B),
    companyZT = Color(0xFF8A4FA3),
    companyZF = Color(0xFF2F62A8),
    companyGL = Color(0xFFC25A2E),
)

val DarkSalesColors = SalesColors(
    line = Color(0xFF4A3A40),
    muted = Color(0xFFB9A5AA),
    accentSoft = Color(0xFF4A2630),
    goldSoft = Color(0xFF4A3A1A),
    gold = Color(0xFFE2B85E),
    good = Color(0xFF7AD08E),
    goodSoft = Color(0xFF1F3A27),
    bad = Color(0xFFFF8A80),
    badSoft = Color(0xFF4A2220),
    surface2 = Color(0xFF33252A),
    companyLV = Color(0xFFE2B85E),
    companyZT = Color(0xFFC592DA),
    companyZF = Color(0xFF7FA8E6),
    companyGL = Color(0xFFE8895C),
)

val LocalSalesColors = staticCompositionLocalOf { LightSalesColors }

// The extra brand colours of the current theme: MaterialTheme.sales.line, .muted and so on.
val MaterialTheme.sales: SalesColors
    @Composable
    get() = LocalSalesColors.current

// Each company has its own colour.
@Composable
fun companyColor(code: String): Color = companyColorIn(MaterialTheme.sales, code)

fun companyColorIn(colors: SalesColors, code: String): Color = when (code) {
    "LV" -> colors.companyLV
    "ZT" -> colors.companyZT
    "ZF" -> colors.companyZF
    "GL" -> colors.companyGL
    else -> colors.muted
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
    error = Color(0xFFFF8A80),
    outline = Color(0xFF4A3A40),
    surfaceVariant = Color(0xFF33252A),
    onSurfaceVariant = Color(0xFFB9A5AA),
)

private val Base = Typography()

private fun slot(size: Int, weight: FontWeight, line: Int, spacing: Double, base: TextStyle) =
    base.copy(fontSize = size.sp, fontWeight = weight, lineHeight = line.sp, letterSpacing = spacing.sp)

/**
 * Every font size in the app lives here. Slots:
 * headlineLarge login title; headlineSmall Home first name;
 * titleLarge sheet name, PIN titles, large avatar initials; titleMedium top bar title, person name;
 * titleSmall card titles, PrimaryButton text, small avatar initials;
 * bodyLarge body and fields; bodyMedium body and chip text; bodySmall notes and sub lines;
 * labelLarge Secondary/Danger button text, ChoiceButton, filter chips, tile name;
 * labelMedium upper-case form labels, section and group headers; labelSmall pills and badges;
 * Typography.caption tile sub-line and product code.
 */
val SalesTypography = Base.copy(
    headlineLarge = slot(34, FontWeight.Bold, 40, 0.0, Base.headlineLarge),
    headlineMedium = slot(28, FontWeight.Bold, 36, 0.0, Base.headlineMedium),
    headlineSmall = slot(24, FontWeight.Bold, 32, 0.0, Base.headlineSmall),
    titleLarge = slot(20, FontWeight.SemiBold, 28, 0.0, Base.titleLarge),
    titleMedium = slot(18, FontWeight.SemiBold, 24, 0.0, Base.titleMedium),
    titleSmall = slot(16, FontWeight.SemiBold, 22, 0.0, Base.titleSmall),
    bodyLarge = slot(16, FontWeight.Normal, 24, 0.5, Base.bodyLarge),
    bodyMedium = slot(15, FontWeight.Normal, 22, 0.25, Base.bodyMedium),
    bodySmall = slot(14, FontWeight.Normal, 20, 0.25, Base.bodySmall),
    labelLarge = slot(15, FontWeight.SemiBold, 20, 0.1, Base.labelLarge),
    labelMedium = slot(13, FontWeight.Medium, 18, 0.5, Base.labelMedium),
    labelSmall = slot(11, FontWeight.SemiBold, 14, 0.3, Base.labelSmall),
)

val Typography.caption: TextStyle
    get() = labelMedium.copy(fontSize = 12.sp, fontWeight = FontWeight.Normal, letterSpacing = 0.25.sp, lineHeight = 16.sp)

/**
 * The phone's own colour scheme (Material You on Android 12 and later), or null where the
 * platform has none, in which case the brand colours are used.
 */
@Composable
internal expect fun platformColorScheme(darkTheme: Boolean): ColorScheme?

/**
 * App theme. With [dynamicColor] on, Android 12+ phones use their wallpaper colours instead of
 * burgundy and gold; it is off by default to keep the brand look. [darkTheme] follows the
 * phone's light or dark mode; screens take all their colours from colorScheme and [sales].
 */
@Composable
fun SalesTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val colors = (if (dynamicColor) platformColorScheme(darkTheme) else null)
        ?: if (darkTheme) DarkColors else LightColors
    val sales = if (darkTheme) DarkSalesColors else LightSalesColors
    CompositionLocalProvider(LocalSalesColors provides sales) {
        MaterialTheme(colorScheme = colors, typography = SalesTypography, content = content)
    }
}
