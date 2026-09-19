package com.focusblock.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// Identity colour
//
// AppBlock -- the reference app -- is vivid blue on deep navy. An earlier pass
// here went lime on near-black, which followed a prototype rather than the
// reference and drifted further from it than the app's own original blue had
// been. Corrected: blue is the identity.
//
// Blue carries both identity and action, so "live" and "tappable" are
// distinguished by fill and weight rather than by a second hue: a live state is
// a tinted surface with a blue border, an action is a solid blue pill.
// ============================================================================
val Signal = Color(0xFF2E6FF2)
val SignalDim = Color(0xFF2A5BBF)
val SignalGlow = Color(0x1F2E6FF2)
val SignalBorder = Color(0x732E6FF2)
val SignalSoft = Color(0xFF1E2F5C)

// Primary Brand Colors
val Primary = Color(0xFF2E6FF2)
val PrimaryVariant = Color(0xFF0066CC)
val PrimaryLight = Color(0xFF4DA3FF)
val PrimaryDark = Color(0xFF0055AA)

// Background Colors - Refined depth system
// Deeper than before: the accent needs somewhere dark to sit.
val BackgroundDark = Color(0xFF0B0F1A)
val BackgroundDarkSecondary = Color(0xFF0F141A)
val BackgroundDarkTertiary = Color(0xFF151B23)
val SurfaceDark = Color(0xFF131A2B)
val SurfaceElevated = Color(0xFF1E283D)
val CardDark = Color(0xFF172033)
val CardDarkElevated = Color(0xFF212A38)

// Text Colors - Better contrast hierarchy
val TextPrimary = Color(0xFFFFFFFF)
val TextSecondary = Color(0xFF9BA6B2)
val TextTertiary = Color(0xFF5C6570)
val TextMuted = Color(0xFF484F58)

// Accent Colors
val AccentBlue = Color(0xFF0A84FF)
val AccentGreen = Color(0xFF3FB950)
val AccentRed = Color(0xFFF85149)
val AccentOrange = Color(0xFFF0883E)
val AccentPurple = Color(0xFFA371F7)
val AccentPink = Color(0xFFFF6B9D)
val AccentCyan = Color(0xFF56D4DD)

// Status Colors
val StatusActive = Color(0xFF3FB950)
val StatusInactive = Color(0xFF6E7681)
val StatusWarning = Color(0xFFF0883E)
val StatusError = Color(0xFFF85149)
val SuccessGreen = Color(0xFF3FB950)
val ErrorRed = Color(0xFFF85149)
val WarningOrange = Color(0xFFF0883E)

// App Category Colors
val DistractiveColor = Color(0xFF8B5CF6) // Purple
val NeutralColor = Color(0xFF06B6D4) // Cyan
val ProductiveColor = Color(0xFF10B981) // Green

// Overlay Colors
val OverlayBackground = Color(0xE6000000)
val OverlayCard = Color(0xFF1E2430)

// Schedule Card Colors
val ScheduleWork = Color(0xFF0A84FF)
val ScheduleSleep = Color(0xFFA371F7)
val ScheduleStudy = Color(0xFF3FB950)
val ScheduleFamily = Color(0xFFFF6B9D)
val ScheduleSocial = Color(0xFFF85149)
val ScheduleDetox = Color(0xFF56D4DD)

// Gradient Colors
val GradientStart = Color(0xFF0A84FF)
val GradientEnd = Color(0xFFA371F7)

// Surface Borders for subtle separation
val SurfaceBorder = Color(0xFF252D38)
val SurfaceBorderLight = Color(0xFF2D3748)

// Divider
val Divider = Color(0xFF252D38)

// Ripple
val Ripple = Color(0x33FFFFFF)

// Shimmer/Glow effects
val GlowPrimary = Color(0x330A84FF)
val GlowSuccess = Color(0x333FB950)
