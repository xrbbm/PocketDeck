package com.xrbbm.pideck.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Catppuccin Latte (light) — slot-aligned with One Dark Pro (dark),
// ported from Flutter main.dart buildLightTheme/buildDarkTheme.
// Public for the WCAG AA contrast test (mirrors pairing_store_test.dart).
object PiDeckPalette {
    val LatteBase = Color(0xFFEFF1F5)
    val LatteMantle = Color(0xFFE6E9EF)
    val LatteCrust = Color(0xFFDCE0E8)
    val LatteText = Color(0xFF4C4F69)
    val LatteSubtext = Color(0xFF5C5F77)
    val LatteBubble = Color(0xFFCCD0DA)
    val LatteBlue = Color(0xFF0B57D0)
    val LatteTeal = Color(0xFF0E7377)
    val LatteWarn = Color(0xFFB54708)
    val LatteRed = Color(0xFFD20F39)

    val OneDarkBg = Color(0xFF282C34)
    val OneDarkDeep = Color(0xFF21252B)
    val OneDarkFg = Color(0xFFABB2BF)
    val OneDarkPanel = Color(0xFF2C313C)
    val OneDarkBubble = Color(0xFF3E4451)
    val OneDarkBlue = Color(0xFF61AFEF)
    val OneDarkCyan = Color(0xFF56B6C2)
    val OneDarkWarn = Color(0xFFF5B841)
    val OneDarkRed = Color(0xFFE57A7D)
}

private val LatteBase = PiDeckPalette.LatteBase
private val LatteMantle = PiDeckPalette.LatteMantle
private val LatteCrust = PiDeckPalette.LatteCrust
private val LatteText = PiDeckPalette.LatteText
private val LatteSubtext = PiDeckPalette.LatteSubtext
private val LatteBubble = PiDeckPalette.LatteBubble
private val LatteBlue = PiDeckPalette.LatteBlue
private val LatteTeal = PiDeckPalette.LatteTeal
private val LatteWarn = PiDeckPalette.LatteWarn
private val LatteRed = PiDeckPalette.LatteRed

private val OneDarkBg = PiDeckPalette.OneDarkBg
private val OneDarkDeep = PiDeckPalette.OneDarkDeep
private val OneDarkFg = PiDeckPalette.OneDarkFg
private val OneDarkPanel = PiDeckPalette.OneDarkPanel
private val OneDarkBubble = PiDeckPalette.OneDarkBubble
private val OneDarkBlue = PiDeckPalette.OneDarkBlue
private val OneDarkCyan = PiDeckPalette.OneDarkCyan
private val OneDarkWarn = PiDeckPalette.OneDarkWarn
private val OneDarkRed = PiDeckPalette.OneDarkRed

val LightColors = lightColorScheme(
    primary = LatteBlue,
    onPrimary = Color.White,
    secondary = LatteTeal,
    tertiary = LatteWarn,
    onTertiary = Color.White,
    tertiaryContainer = LatteMantle,
    onTertiaryContainer = LatteText,
    primaryContainer = LatteBubble,
    onPrimaryContainer = LatteText,
    surface = LatteBase,
    onSurface = LatteText,
    onSurfaceVariant = LatteSubtext,
    surfaceContainerLowest = LatteCrust,
    error = LatteRed,
)

val DarkColors = darkColorScheme(
    primary = OneDarkBlue,
    onPrimary = OneDarkBg,
    secondary = OneDarkCyan,
    tertiary = OneDarkWarn,
    onTertiary = OneDarkBg,
    tertiaryContainer = OneDarkPanel,
    onTertiaryContainer = OneDarkFg,
    primaryContainer = OneDarkBubble,
    onPrimaryContainer = Color.White,
    surface = OneDarkBg,
    onSurface = OneDarkFg,
    surfaceContainerLowest = OneDarkDeep,
    error = OneDarkRed,
)

@Composable
fun PiDeckTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}

/**
 * Web 1:1 theme (zinc tokens from the web CSS). When [web] is true the
 * whole app renders with the web palette; otherwise the legacy
 * Catppuccin/OneDark palette. Defaults to web (user asked for 1:1).
 */
@Composable
fun AppTheme(dark: Boolean, web: Boolean = true, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = when {
            web && dark -> WebDarkColors
            web -> WebLightColors
            dark -> DarkColors
            else -> LightColors
        },
        content = content,
    )
}
