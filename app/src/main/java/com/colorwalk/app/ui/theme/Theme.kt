package com.colorwalk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

enum class ThemeMode { DARK, LIGHT, SYSTEM }

/**
 * Full Material 3 color roles, bold & playful: a vivid violet primary, teal
 * secondary, and slightly violet-tinted neutrals. The DAILY walk color remains
 * the visual hero on Home/Camera — these scheme colors carry everything else
 * (settings, chips, dialogs) so the daily accent never has to compete.
 *
 * All on-colors checked against their surfaces for WCAG AA contrast.
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFFC4A9FF),
    onPrimary = Color(0xFF30135E),
    primaryContainer = Color(0xFF472A82),
    onPrimaryContainer = Color(0xFFE8DDFF),
    secondary = Color(0xFF6FD6C4),
    onSecondary = Color(0xFF003730),
    secondaryContainer = Color(0xFF005046),
    onSecondaryContainer = Color(0xFF8FF2DF),
    tertiary = Color(0xFFFFB59C),
    onTertiary = Color(0xFF55200A),
    background = Color(0xFF121117),
    onBackground = Color(0xFFE8E5EE),
    surface = Color(0xFF1C1B22),
    onSurface = Color(0xFFE8E5EE),
    surfaceVariant = Color(0xFF2B2933),
    onSurfaceVariant = Color(0xFFB9B4C4),
    outline = Color(0xFF59555F),
    outlineVariant = Color(0xFF3B3942),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005)
)

// Warm paper-white canvas with pure-white cards so photos sit on a gallery wall.
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF6442D6),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8DDFF),
    onPrimaryContainer = Color(0xFF21005D),
    secondary = Color(0xFF006B5D),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFF9CF2E0),
    onSecondaryContainer = Color(0xFF00201B),
    tertiary = Color(0xFF96490C),
    onTertiary = Color(0xFFFFFFFF),
    background = Color(0xFFFAF8FD),
    onBackground = Color(0xFF1C1B20),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF1C1B20),
    surfaceVariant = Color(0xFFEDEAF4),
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFF7A7682),
    outlineVariant = Color(0xFFCBC7D3),
    error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF)
)

@Composable
fun ColorWalkTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val dark = when (themeMode) {
        ThemeMode.DARK   -> true
        ThemeMode.LIGHT  -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColorScheme else LightColorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
