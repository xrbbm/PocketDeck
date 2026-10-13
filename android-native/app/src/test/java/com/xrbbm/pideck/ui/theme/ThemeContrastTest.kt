package com.xrbbm.pideck.ui.theme

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** WCAG AA contrast gates, ported from Flutter pairing_store_test.dart. */
class ThemeContrastTest {
    private fun channel(v: Float): Double {
        val d = v.toDouble()
        return if (d <= 0.03928) d / 12.92 else ((d + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(c: Color): Double =
        0.2126 * channel(c.red) + 0.7152 * channel(c.green) + 0.0722 * channel(c.blue)

    private fun ratio(a: Color, b: Color): Double {
        val la = luminance(a)
        val lb = luminance(b)
        return if (la > lb) (la + 0.05) / (lb + 0.05) else (lb + 0.05) / (la + 0.05)
    }

    @Test
    fun `dark card text contrast meets AA`() {
        val cs = DarkColors
        // Approval card body + text.
        assertTrue(ratio(cs.tertiaryContainer, cs.onTertiaryContainer) >= 4.5)
        // User bubble body + text.
        assertTrue(ratio(cs.primaryContainer, cs.onPrimaryContainer) >= 4.5)
    }

    @Test
    fun `functional colors meet AA`() {
        val light = LightColors
        // Light primary as body link color (on light surface).
        assertTrue(ratio(light.primary, light.surface) >= 4.5)
        // Light button white text (on primary).
        assertTrue(ratio(light.onPrimary, light.primary) >= 4.5)
        // Light secondary badge color (on light surface).
        assertTrue(ratio(light.secondary, light.surface) >= 4.5)
        // Light tertiary usage-ring warning (on light surface).
        assertTrue(ratio(light.tertiary, light.surface) >= 4.5)
        // Light button white text (on warning).
        assertTrue(ratio(light.onTertiary, light.tertiary) >= 4.5)
        val dark = DarkColors
        // Dark error text (on dark surface).
        assertTrue(ratio(dark.error, dark.surface) >= 4.5)
        // Dark tertiary usage-ring warning (on dark surface).
        assertTrue(ratio(dark.tertiary, dark.surface) >= 4.5)
    }
}
