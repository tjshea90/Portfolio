package com.tj.portfolio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/** Sampled from the user's reference screenshots. */
val Green = Color(0xFF16C784)
val Red = Color(0xFFEA4B5D)
val Accent = Color(0xFF2E6BE6)

/**
 * The benchmark line on a comparison chart (Round 63).
 *
 * DELIBERATELY NOT [Green], [Red] OR [Accent]. Green and red already mean up and down
 * everywhere in this app, and painting a second line in either would say something about its
 * direction that is not being said. [Accent] is the selection colour and is already on the
 * screen - on the chosen range chip, directly above the chart. A muted amber is the only
 * thing left that reads clearly against both themes and claims nothing.
 */
val Benchmark = Color(0xFFB4863B)

fun signColor(v: Double): Color = if (v >= 0) Green else Red

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF10131A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10131A),
    surfaceVariant = Color(0xFFF3F5F8),
    onSurfaceVariant = Color(0xFF6B7280),
    outline = Color(0xFFE3E7EC),
    error = Red
)

private val DarkColors = darkColorScheme(
    primary = Accent,
    onPrimary = Color.White,
    background = Color(0xFF0E1013),
    onBackground = Color(0xFFF2F4F7),
    surface = Color(0xFF14171C),
    onSurface = Color(0xFFF2F4F7),
    surfaceVariant = Color(0xFF1C2027),
    onSurfaceVariant = Color(0xFF9AA3AF),
    outline = Color(0xFF272C34),
    error = Red
)

// Overriding a Typography slot replaces the Material default outright, including its
// lineHeight - so without these the long explanatory paragraphs in Settings, the Advice
// reasoning and the news summaries all rendered at the font's own cramped default leading.
private val AppTypography = Typography(
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 23.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 15.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontSize = 13.sp, lineHeight = 18.sp),
    bodySmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 13.sp, fontWeight = FontWeight.Medium)
)

@Composable
fun PortfolioTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = AppTypography,
        content = content
    )
}
