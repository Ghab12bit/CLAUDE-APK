package com.focusblock.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// The signal colour has to live IN the scheme, not beside it.
//
// Adding Signal to the palette while leaving primary as the old blue meant
// every Button, FAB, Switch, Checkbox, progress spinner and the bottom-nav
// pill still rendered blue -- i.e. every component the eye actually lands on.
// The redesign was real but invisible, because it only reached a handful of
// conditionally-coloured views.
private val DarkColorScheme = darkColorScheme(
    primary = Signal,
    onPrimary = Color(0xFF07090D),
    primaryContainer = SignalGlow,
    onPrimaryContainer = Signal,
    secondary = Primary,
    onSecondary = Color.White,
    secondaryContainer = Primary.copy(alpha = 0.25f),
    onSecondaryContainer = Color.White,
    tertiary = AccentCyan,
    onTertiary = Color.Black,
    background = BackgroundDark,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = CardDark,
    onSurfaceVariant = TextSecondary,
    outline = Divider,
    error = AccentRed,
    onError = Color.White,
    errorContainer = AccentRed.copy(alpha = 0.3f),
    onErrorContainer = Color.White
)

@Composable
fun FocusBlockTheme(
    darkTheme: Boolean = true, // Always dark theme for AppBlock-like look
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = BackgroundDark.toArgb()
            window.navigationBarColor = BackgroundDark.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
