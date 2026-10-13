package com.xrbbm.pideck.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Catppuccin Latte (light) — slot-aligned with One Dark Pro (dark),
// ported from Flutter main.dart buildLightTheme/buildDarkTheme.
private val LatteBase = Color(0xFFEFF1F5)
private val LatteMantle = Color(0xFFE6E9EF)
private val LatteCrust = Color(0xFFDCE0E8)
private val LatteText = Color(0xFF4C4F69)
private val LatteSubtext = Color(0xFF5C5F77)
private val LatteBubble = Color(0xFFCCD0DA)
private val LatteBlue = Color(0xFF0B57D0)
private val LatteTeal = Color(0xFF0E7377)
private val LatteWarn = Color(0xFFB54708)
private val LatteRed = Color(0xFFD20F39)

private val OneDarkBg = Color(0xFF282C34)
private val OneDarkDeep = Color(0xFF21252B)
private val OneDarkFg = Color(0xFFABB2BF)
private val OneDarkPanel = Color(0xFF2C313C)
private val OneDarkBubble = Color(0xFF3E4451)
private val OneDarkBlue = Color(0xFF61AFEF)
private val OneDarkCyan = Color(0xFF56B6C2)
private val OneDarkWarn = Color(0xFFF5B841)
private val OneDarkRed = Color(0xFFE57A7D)

private val LightColors = lightColorScheme(
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

private val DarkColors = darkColorScheme(
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
