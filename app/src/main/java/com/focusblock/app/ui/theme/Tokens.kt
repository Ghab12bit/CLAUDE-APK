package com.focusblock.app.ui.theme

import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Design tokens (spec 5.2). Composables use these, never raw colours. Dark is the default; every
 * text/background pair is checked against WCAG AA in TokensContrastTest.
 */
object Fb {
    val bg = Color(0xFF0B0D14)
    val surface = Color(0xFF161A26)
    /** Raised elements inside a card: selected pills, tiles, icon wells. */
    val surfaceHigh = Color(0xFF20253A)
    /** Blue-tinted surface for the running block. */
    val surfaceActive = Color(0xFF1B2547)
    val textPrimary = Color(0xFFF4F6FB)
    val textSecondary = Color(0xFFA3ABC2)
    val divider = Color(0xFFF4F6FB).copy(alpha = 0.08f)
    /** Vivid blue: links, selected state, neutral usage. */
    val accent = Color(0xFF6C9BFF)
    /** Violet: the second brand colour and distracting usage. */
    val accentAlt = Color(0xFFB18CFF)
    /** Primary buttons are a blue-to-violet gradient with white text (both ends pass 4.5:1). */
    val buttonPrimaryBg = Color(0xFF3366FF)
    val buttonPrimaryBgEnd = Color(0xFF7A4DFF)
    val buttonPrimaryText = Color(0xFFFFFFFF)
    val success = Color(0xFF3DDC97)
    val warning = Color(0xFFFF8A6B)
    val disabled = Color(0xFFF4F6FB).copy(alpha = 0.38f)
    val track = Color(0xFFF4F6FB).copy(alpha = 0.10f)

    // Usage categories (Activity tab).
    val distracting = accentAlt
    val neutral = accent
    val productive = success
    /** Highlights the peak hour. */
    val peak = warning

    // Spacing: 4-pt grid.
    val gutter = 20.dp
    val sectionGap = 24.dp
    val touch = 48.dp
    val radius = 16.dp
    val cardRadius = 22.dp
}

/** One sans family; tabular figures for every time and number (spec 5.2). */
object FbType {
    private val family = FontFamily.SansSerif
    private const val TNUM = "tnum"
    val display = TextStyle(fontFamily = family, fontSize = 44.sp, lineHeight = 50.sp, fontWeight = FontWeight.Bold, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val title = TextStyle(fontFamily = family, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val heading = TextStyle(fontFamily = family, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val body = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 22.sp, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val label = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val caption = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM, color = Fb.textSecondary)
    /** Small capitals-style label above big numbers ("SCREEN TIME"). */
    val overline = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, fontFeatureSettings = TNUM, color = Fb.textSecondary)
}

@Composable
fun FbTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Fb.accent,
        onPrimary = Fb.bg,
        secondary = Fb.accentAlt,
        background = Fb.bg,
        onBackground = Fb.textPrimary,
        surface = Fb.surface,
        onSurface = Fb.textPrimary,
        surfaceVariant = Fb.surface,
        onSurfaceVariant = Fb.textSecondary,
        outline = Fb.divider,
        error = Fb.warning,
        onError = Fb.bg,
    )
    MaterialTheme(colorScheme = scheme) {
        CompositionLocalProvider(LocalTextSelectionColors provides TextSelectionColors(Fb.accent, Fb.accent.copy(alpha = 0.3f))) {
            content()
        }
    }
}
