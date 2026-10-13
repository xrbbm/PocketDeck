package com.xrbbm.pideck.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

/**
 * Web 1:1 tokens (from http://192.168.50.210:8765/assets/web-D16H8S27.css).
 *
 * Light (:root) / Dark (:root[data-theme=dark]). Slot mapping:
 * bg-app -> surface, bg-sidebar/panel -> surfaceContainer*, text-primary ->
 * onSurface, accent -> primary, info/tool -> secondary, thinking -> tertiary.
 */
object WebTokens {
    // Light
    val BgApp = Color(0xFFFFFFFF)
    val BgSidebar = Color(0xFFF9FAFB)
    val BgPanel = Color(0xFFFFFFFF)
    val BgPopover = Color(0xFFFFFFFF)
    val BgInput = Color(0xFFFFFFFF)
    val BgMuted = Color(0xFFEBEDF2)
    val BgHover = Color(0xFFE1E5EE)
    val BgActive = Color(0xFFCFD3D6)
    val ChatCardBg = Color(0xFFF9FAFB)
    val ChatMutedBg = Color(0xFFF4F5F7)
    val TextPrimary = Color(0xFF0F1115)
    val TextSecondary = Color(0xFF61666B)
    val TextTertiary = Color(0xFF81858C)
    val Accent = Color(0xFF18181B)
    val AccentStrong = Color(0xFF09090B)
    val AccentSoft = Color(0xFFEBEDF2)
    val Danger = Color(0xFFEF4444)
    val DangerSoft = Color(0xFFFEF2F2)
    val Success = Color(0xFF22C55E)
    val SuccessSoft = Color(0xFFF0FDF4)
    val SuccessStrong = Color(0xFF16A34A)
    val Warning = Color(0xFFF59E0B)
    val Info = Color(0xFF3B82F6)
    val Thinking = Color(0xFF6366F1)
    val CodeBg = Color(0xFF171A26)
    val CodeText = Color(0xFFD1D5DB)

    // Dark
    val DarkBgApp = Color(0xFF0D0F14)
    val DarkBgSidebar = Color(0xFF131620)
    val DarkBgPanel = Color(0xFF131620)
    val DarkBgPopover = Color(0xFF1A1E2C)
    val DarkBgInput = Color(0xFF0D0F14)
    val DarkBgMuted = Color(0xFF1A1E2C)
    val DarkBgHover = Color(0xFF232838)
    val DarkBgActive = Color(0xFF2A3042)
    val DarkChatCardBg = Color(0xFF131620)
    val DarkChatMutedBg = Color(0xFF171A26)
    val DarkTextPrimary = Color(0xFFE8EAF2)
    val DarkTextSecondary = Color(0xFF9AA1B5)
    val DarkTextTertiary = Color(0xFF6B7288)
    val DarkAccent = Color(0xFFE8EAF2)
    val DarkAccentStrong = Color(0xFFFFFFFF)
    val DarkAccentSoft = Color(0xFF232838)
    val DarkDanger = Color(0xFFF87171)
    val DarkSuccess = Color(0xFF34D399)
    val DarkSuccessStrong = Color(0xFF6EE7B7)
    val DarkWarning = Color(0xFFFBBF24)
    val DarkInfo = Color(0xFF60A5FA)
    val DarkThinking = Color(0xFF818CF8)
}

val WebLightColors = lightColorScheme(
    primary = WebTokens.Accent,
    onPrimary = Color.White,
    secondary = WebTokens.Info,
    tertiary = WebTokens.Thinking,
    error = WebTokens.Danger,
    surface = WebTokens.BgApp,
    onSurface = WebTokens.TextPrimary,
    onSurfaceVariant = WebTokens.TextSecondary,
    surfaceContainerLowest = WebTokens.BgSidebar,
    surfaceContainerLow = WebTokens.ChatCardBg,
    surfaceContainer = WebTokens.BgMuted,
    surfaceContainerHigh = WebTokens.BgHover,
    surfaceContainerHighest = WebTokens.BgActive,
    primaryContainer = WebTokens.AccentSoft,
    onPrimaryContainer = WebTokens.TextPrimary,
    tertiaryContainer = WebTokens.BgMuted,
    onTertiaryContainer = WebTokens.TextPrimary,
)

val WebDarkColors = darkColorScheme(
    primary = WebTokens.DarkAccent,
    onPrimary = WebTokens.DarkBgApp,
    secondary = WebTokens.DarkInfo,
    tertiary = WebTokens.DarkThinking,
    error = WebTokens.DarkDanger,
    surface = WebTokens.DarkBgApp,
    onSurface = WebTokens.DarkTextPrimary,
    onSurfaceVariant = WebTokens.DarkTextSecondary,
    surfaceContainerLowest = WebTokens.DarkBgSidebar,
    surfaceContainerLow = WebTokens.DarkChatCardBg,
    surfaceContainer = WebTokens.DarkBgMuted,
    surfaceContainerHigh = WebTokens.DarkBgHover,
    surfaceContainerHighest = WebTokens.DarkBgActive,
    primaryContainer = WebTokens.DarkAccentSoft,
    onPrimaryContainer = WebTokens.DarkTextPrimary,
    tertiaryContainer = WebTokens.DarkBgMuted,
    onTertiaryContainer = WebTokens.DarkTextPrimary,
)
