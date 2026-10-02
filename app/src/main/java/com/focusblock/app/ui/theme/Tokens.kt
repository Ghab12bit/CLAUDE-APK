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
    val bg = Color(0xFF151411)
    val surface = Color(0xFF201E19)
    val surfaceActive = Color(0xFF3A2A1E)
    val textPrimary = Color(0xFFF2EEE4)
    val textSecondary = Color(0xFFA8A196)
    val divider = Color(0xFFF2EEE4).copy(alpha = 0.10f)
    val accent = Color(0xFFE6A44A)
    val buttonPrimaryBg = Color(0xFFF2EEE4)
    val buttonPrimaryText = Color(0xFF151411)
    val success = Color(0xFF8FA88A)
    /** Restrained rust, lightened from #C46A4A so body text stays above 4.5:1 on [surface]. */
    val warning = Color(0xFFD07656)
    val disabled = Color(0xFFF2EEE4).copy(alpha = 0.38f)
    val track = Color(0xFFF2EEE4).copy(alpha = 0.16f)

    // Spacing: 4-pt grid.
    val gutter = 20.dp
    val sectionGap = 24.dp
    val touch = 48.dp
    val radius = 12.dp
}

/** One sans family; tabular figures for every time and number (spec 5.2). */
object FbType {
    private val family = FontFamily.SansSerif
    private const val TNUM = "tnum"
    val display = TextStyle(fontFamily = family, fontSize = 40.sp, lineHeight = 46.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val title = TextStyle(fontFamily = family, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.Normal, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val heading = TextStyle(fontFamily = family, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val body = TextStyle(fontFamily = family, fontSize = 16.sp, lineHeight = 22.sp, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val label = TextStyle(fontFamily = family, fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium, fontFeatureSettings = TNUM, color = Fb.textPrimary)
    val caption = TextStyle(fontFamily = family, fontSize = 12.sp, lineHeight = 16.sp, fontFeatureSettings = TNUM, color = Fb.textSecondary)
}

@Composable
fun FbTheme(content: @Composable () -> Unit) {
    val scheme = darkColorScheme(
        primary = Fb.accent,
        onPrimary = Fb.bg,
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
