package com.focusblock.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/** Spec 5.2: every text/background pair meets WCAG AA (4.5:1 for body text). */
class TokensContrastTest {
    private fun channel(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }

    private fun luminance(color: Color): Double {
        val argb = color.toArgb()
        return 0.2126 * channel((argb shr 16) and 0xFF) + 0.7152 * channel((argb shr 8) and 0xFF) + 0.0722 * channel(argb and 0xFF)
    }

    private fun ratio(a: Color, b: Color): Double {
        val l1 = luminance(a)
        val l2 = luminance(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    @Test fun bodyTextPairsMeetAA() {
        val pairs = listOf(
            "textPrimary/bg" to ratio(Fb.textPrimary, Fb.bg),
            "textSecondary/bg" to ratio(Fb.textSecondary, Fb.bg),
            "textPrimary/surface" to ratio(Fb.textPrimary, Fb.surface),
            "textSecondary/surface" to ratio(Fb.textSecondary, Fb.surface),
            "textPrimary/surfaceActive" to ratio(Fb.textPrimary, Fb.surfaceActive),
            "textSecondary/surfaceActive" to ratio(Fb.textSecondary, Fb.surfaceActive),
            "accent/bg" to ratio(Fb.accent, Fb.bg),
            "accent/surface" to ratio(Fb.accent, Fb.surface),
            "accent/surfaceActive" to ratio(Fb.accent, Fb.surfaceActive),
            "warning/bg" to ratio(Fb.warning, Fb.bg),
            "warning/surface" to ratio(Fb.warning, Fb.surface),
            "success/bg" to ratio(Fb.success, Fb.bg),
            "buttonText/buttonBg" to ratio(Fb.buttonPrimaryText, Fb.buttonPrimaryBg),
        )
        pairs.forEach { (name, r) -> assertTrue("$name contrast is %.2f, needs 4.5".format(r), r >= 4.5) }
    }
}
