package com.tj.portfolio.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * WHICH SCHEME IS ACTUALLY IN FORCE (Round 63, sweep 3).
 *
 * The theme-aware text colours below first asked `isSystemInDarkTheme()`, which is where
 * `PortfolioTheme` gets its DEFAULT from - but `PortfolioTheme` takes an override, and seven
 * UI tests use it to render the dark scheme on a light-mode host. Those tests were getting
 * light-theme text painted on dark surfaces, so any contrast assertion in them was measuring
 * the wrong pair. Production was unaffected, which is exactly what makes it the kind of thing
 * that stays wrong: the only place it shows is the place that checks.
 *
 * `PortfolioTheme` publishes the answer here and everything downstream reads it, so the colours
 * can never disagree with the surfaces they sit on.
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

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

/**
 * The same amber, dark enough to carry white text on it (Round 63 sweep).
 *
 * `#B4863B` is right as a LINE on a chart - it reads clearly against both themes and claims
 * nothing, which is why it was chosen. White on it is 3.27:1, which is not enough for the
 * 13sp and 11sp of the "vs SPY" toggle. `#7E5A22` takes that to 6.23:1 and is unmistakably
 * the same colour family, so the toggle and the line it turns on still read as one thing.
 */
val BenchmarkFill = Color(0xFF7E5A22)

/**
 * The benchmark colour to actually paint with, whichever theme is up.
 *
 * ONE COLOUR FOR THE WHOLE FEATURE. The first pass at this used the darker amber for the chip
 * (which carries white text) and the lighter one for the line, its legend dot and both
 * readouts - so with the overlay on, two visibly different ambers described the same thing on
 * the same screen. The line does not need the bright value on white: at 1.4dp it reads better
 * dark, and it then matches the control that switched it on.
 */
val benchmarkColor: Color
    @Composable get() = if (LocalDarkTheme.current) Benchmark else BenchmarkFill

/**
 * What to write ON [benchmarkColor], which is not the same answer in both themes.
 *
 * Unifying the amber fixed one problem and reintroduced another: the "vs SPY" chip carries
 * 13sp and 11sp text on that fill, and white on the light `#B4863B` is 3.27:1. The fill has to
 * stay as it is - it is the line's colour and the chip must match the line - so the TEXT
 * moves instead: near-black on the bright amber is 5.42:1, white on the dark one is 6.23:1.
 */
val onBenchmark: Color
    @Composable get() = if (LocalDarkTheme.current) Color(0xFF141821) else Color.White

/**
 * [Accent] as TEXT, brightened for the dark theme (Round 63 sweep).
 *
 * `#2E6BE6` on the light surfaceVariant is 4.41:1 - a hair under AA and acceptable at the
 * sizes it is used - but on the DARK surfaceVariant it is 3.39:1, and the place that hurts is
 * the "News" chip on every holding row, which is the most-tapped control on the portfolio
 * list. `#5B92F0` takes the dark case to 5.30:1; the light case keeps the brand colour, where
 * the brighter blue would go the wrong way (2.82:1).
 */
val AccentTextDark = Color(0xFF5B92F0)

/** Accent as TEXT: the brand blue on light, a brighter one on dark. */
val accentText: Color
    @Composable get() = if (LocalDarkTheme.current) AccentTextDark else Accent

/**
 * The Research card's score colour, by tier - and legible in both themes.
 *
 * The three tiers were a single set of literals, tuned against the dark theme like the rest of
 * the palette. On white the middling amber measured 2.46:1, which is worse than the green this
 * sweep started from and it was carrying the actual number. Each tier now has a light-theme
 * value and a dark-theme one; every combination is above 4.5:1 against its own background.
 *
 * NO `bullish` PARAMETER SINCE ROUND 66. It said what the SECTION meant rather than what the
 * number said, because a 90 on the "Worst" list was a strong finding about a bad company and
 * painting it green would have been exactly wrong. That section is gone, every remaining
 * caller passed `true`, and the bearish half of each tier was unreachable.
 */
@Composable
fun scoreColor(score: Int): Color {
    val dark = LocalDarkTheme.current
    return when {
        score >= 70 -> greenText
        score >= 50 -> if (dark) Color(0xFF3D9A5B) else Color(0xFF2F7A46)
        else -> if (dark) Color(0xFFD79A2B) else Color(0xFF8A6410)
    }
}


/**
 * ---- THE SAME GREEN IS NOT LEGIBLE ON BOTH BACKGROUNDS (Round 63 sweep).
 *
 * [Green] and [Red] were sampled from TJ's reference screenshots and they are right for what
 * they were sampled as: FILLS. A chart line, a sparkline, a gradient, a chip - large shapes
 * where the eye reads the colour, not the edges.
 *
 * Measured as TEXT they are a different story. On the light theme's white surface, `#16C784`
 * against `#FFFFFF` is a contrast ratio of **2.20:1**, where WCAG AA asks 4.5:1 for text at
 * these sizes; `#EA4B5D` is 3.71:1. In the dark theme the same two are 8.07:1 and 4.79:1 - so
 * the palette was tuned there and never re-checked against light. The worst case in the app
 * was "you own this" in green at 10sp on white, which is the smallest type there is.
 *
 * So the fill colours are untouched, and TEXT gets a darker pair in the light theme only:
 * `#0A8055` is 4.96:1 and `#C62B3C` is 5.51:1, both still plainly the same green and the same
 * red. In the dark theme these resolve to the originals, because there they already pass.
 *
 * NOTE THIS IS ABOUT CONTRAST, NOT ABOUT MEANING. The app never uses colour as the only
 * carrier of a sign - `Fmt.usdSigned` and `Fmt.pctSigned` always print a leading + or -, the
 * feed writes "Bullish" and "up 3 places" in words, and the ETF fact cells deliberately refuse
 * to colour an unsigned figure. That discipline is why this was only ever a legibility fault.
 */
val GreenTextLight = Color(0xFF0A8055)
val RedTextLight = Color(0xFFC62B3C)

/**
 * RED AS TEXT ON THE DARK THEME - not the brand red (Round 66 audit, found by [ContrastTest]).
 *
 * The dark half of this rule used to be `Red` itself, on the reasoning that a bright colour
 * is legible on a dark ground. It is, on the BACKGROUND (5.14:1). The `StatCard` is
 * `surfaceVariant`, two shades lighter, and there `Red` measures **4.41:1** - a hair under
 * the 4.5:1 AA asks of body text, on the one figure in the app it is most expensive to
 * misread. Every losing number on the portfolio card sat just below the line, in the dark
 * theme, which is the theme this palette was tuned in.
 *
 * `0xFFF2606F` is the same hue lifted just far enough: 5.20:1 on `surfaceVariant`, 6.06:1 on
 * the background. `Red` is untouched, so the chart, the sparklines and every drawn shape keep
 * the brand colour - the split is text versus fill, exactly as for [greenText].
 */
val RedTextDark = Color(0xFFF2606F)

/** Green as TEXT: darker on a light background, the brand colour on a dark one. */
val greenText: Color
    @Composable get() = if (LocalDarkTheme.current) Green else GreenTextLight

/** Red as TEXT, on the same rule. See [RedTextDark] for why the dark half is not `Red`. */
val redText: Color
    @Composable get() = if (LocalDarkTheme.current) RedTextDark else RedTextLight

/**
 * The colour for a signed FIGURE - which is text, so it follows the rule above.
 *
 * `@Composable` because the answer depends on the theme. Every one of its call sites is
 * already inside composition; nothing outside the UI ever asked this question.
 */
@Composable
/** Zero at the precision the figures are shown at is not a loss (U-7) - see [Fmt.snapZero]. */
fun signColor(v: Double): Color =
    if (com.tj.portfolio.util.Fmt.snapZero(v) >= 0) greenText else redText

/**
 * THE LINE BETWEEN ONE STOCK AND THE NEXT, AS A COLOUR (Round 66 audit, PUI-4).
 *
 * ---- WHY THIS IS NOT `colorScheme.outline`
 *
 * It was, and that is why two rounds of making the bar THICKER did not make it read as more
 * separated. TJ asked twice - *"make the separation between stocks a little more
 * pronounced"*, then *"make the separation bars between stocks even thicker"* - and the rule
 * went 1dp, 3dp, 5dp in reply. Measured, the problem was never the thickness: `outline` is
 * #E3E7EC on a white background, which is a contrast ratio of **1.24:1**, and #272C34 on the
 * dark background, **1.37:1**. A 5dp band at 1.24:1 is a slightly-off-white strip, not a
 * rule. Going to 7dp would have made a wider slightly-off-white strip.
 *
 * These two are measured at **3.14:1** (light) and **3.07:1** (dark) against their own
 * backgrounds - just past the 3:1 WCAG asks of a non-text graphic, and deliberately not
 * further: a divider that out-contrasts the text it separates reads as a table border.
 *
 * `outline` is left exactly as it was, because [InRowDivider] uses it and the hierarchy
 * between the two lines is the whole point - see [RowSeparator].
 */
val rowRule: Color
    @Composable get() = if (LocalDarkTheme.current) Color(0xFF5A616C) else Color(0xFF8A929E)

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
    // `LocalDarkTheme` carries the scheme ACTUALLY in force down the tree - see its own note.
    // Reading `isSystemInDarkTheme()` again further down would ignore this parameter.
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = AppTypography,
            content = content
        )
    }
}
