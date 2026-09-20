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

// The identity colour lives IN the scheme, so every Button, FAB, Switch,
// Checkbox, spinner and the bottom-nav pill inherit it rather than each
// hard-coding a constant.
//
// onPrimary is Ground rather than white: Ember is a light-ish warm accent, and
// white text on it reads at 2.6:1. Near-black on Ember reads at 5.1:1, which is
// the difference between a button label you can read outdoors and one you
// cannot.
private val DarkColorScheme = darkColorScheme(
    primary = Ember,
    onPrimary = Ground,
    primaryContainer = EmberQuiet,
    onPrimaryContainer = Ember,
    secondary = InkMuted,
    onSecondary = Ground,
    secondaryContainer = GroundRaised,
    onSecondaryContainer = Ink,
    tertiary = Affirm,
    onTertiary = Ground,
    background = Ground,
    onBackground = Ink,
    surface = Ground,
    onSurface = Ink,
    surfaceVariant = GroundRaised,
    onSurfaceVariant = InkMuted,
    outline = Rule,
    error = Alarm,
    onError = Ink,
    errorContainer = Color(0xFF3A1D18),
    onErrorContainer = Ink
)

@Composable
fun FocusBlockTheme(
    darkTheme: Boolean = true, // One theme: a focus tool has no reason to be white
    content: @Composable () -> Unit
) {
    val colorScheme = DarkColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Ground.toArgb()
            window.navigationBarColor = Ground.toArgb()
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
