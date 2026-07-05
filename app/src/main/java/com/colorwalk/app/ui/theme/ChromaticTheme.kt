package com.colorwalk.app.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * The day's chromatic identity, distributed app-wide via [LocalDayPalette].
 *
 * @property accent            the walk color of the day
 * @property onAccent          WCAG-compliant content color for text/icons ON the accent
 * @property accentContainer   a muted, background-blended tint of the accent for fills
 * @property onAccentContainer content color for text on [accentContainer]
 */
data class DayPalette(
    val accent: Color,
    val onAccent: Color,
    val accentContainer: Color,
    val onAccentContainer: Color
)

/**
 * Composition-wide handle to the day's palette. [ColorWalkTheme] provides the real
 * value; the default is only a pre-composition placeholder.
 */
val LocalDayPalette = staticCompositionLocalOf {
    dayPaletteFor(accent = Color(0xFF1E88E5), background = Color(0xFF121117))
}

/**
 * Derives the full day palette from the accent using measured WCAG luminance —
 * never hardcoded assumptions about the accent being "dark enough for white text"
 * (Yellow and Orange days are not).
 */
fun dayPaletteFor(accent: Color, background: Color): DayPalette {
    val accentArgb = accent.toArgb()
    val container = Color(Wcag.blend(accentArgb, background.toArgb(), 0.72f))
    return DayPalette(
        accent = accent,
        onAccent = Color(Wcag.contentColorFor(accentArgb)),
        accentContainer = container,
        onAccentContainer = Color(Wcag.contentColorFor(container.toArgb()))
    )
}

/** Convenience accessor mirroring MaterialTheme.colorScheme ergonomics. */
object DayTheme {
    val palette: DayPalette
        @Composable get() = LocalDayPalette.current
}
