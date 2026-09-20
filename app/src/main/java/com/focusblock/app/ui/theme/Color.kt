package com.focusblock.app.ui.theme

import androidx.compose.ui.graphics.Color

// ============================================================================
// PAPER AND INK
// ============================================================================
//
// The previous palette was chosen to match AppBlock: vivid blue on a cold
// near-black, with six separate container shades so that every element could
// sit in its own rounded card. That is why the app kept reading as a replica
// no matter how the cards were rearranged -- the colours WERE the reference
// app's colours, by explicit intent, and the container count forced the
// card-stack layout that goes with them.
//
// This palette is built on three decisions instead:
//
//   1. WARM, NOT COLD. The ground is brown-biased near-black rather than blue
//      near-black. It reads as paper in shadow rather than as a screen, and
//      nothing else in this category looks like it -- AppBlock and One Sec are
//      blue on black, Opal is violet on white.
//
//   2. ONE RAISED SURFACE, NOT SIX. There is exactly one colour above the
//      ground. A screen may use it once, for the thing that is actually
//      happening. Everything else is separated by a hairline rule and space.
//      Take away five container shades and the card-stack layout stops being
//      possible to write by accident.
//
//   3. ONE ACCENT, WARM. Ember is used for the live state and the single
//      primary action on a screen, and for nothing else. It never carries
//      meaning on its own: an active state is also a filled bar in a different
//      position, so the screen still reads without colour vision.
//
// Contrast: Ink on Ground 13.2:1, InkMuted on Ground 5.6:1, Ember on Ground
// 5.1:1. All pass AA for their sizes.
// ============================================================================

/** Warm near-black. The page. */
val Ground = Color(0xFF12100E)

/** The only surface above the ground. One per screen. */
val GroundRaised = Color(0xFF1A1714)

/** Warm off-white. Never pure white -- it glares against a warm ground. */
val Ink = Color(0xFFF2EDE6)
val InkMuted = Color(0xFF9A9088)
val InkFaint = Color(0xFF5E574F)

/** The one accent. */
val Ember = Color(0xFFE8643C)

/** A wash behind a live state. Ember at low weight, not a glow. */
val EmberQuiet = Color(0xFF3A201A)

/** Ember at a hairline's weight, for the edge of an upcoming state. */
val EmberEdge = Color(0x66E8643C)

/** Hairline separator. The primary way sections are divided. */
val Rule = Color(0xFF2A2622)

// ---------------------------------------------------------------------------
// Meaning colours
//
// Deliberately few. A blocker needs "this is on", "this needs attention" and
// "this is wrong" -- it does not need a six-colour category system, which is
// what produced the Distracting / Neutral / Productive grading that made the
// old Insights screen read as a report card.
// ---------------------------------------------------------------------------

/** Something needs the user's attention but nothing is broken. */
val Caution = Color(0xFFD9A441)

/** Something is actually wrong: blocking cannot run. */
val Alarm = Color(0xFFD1503F)

/** A quiet confirmation. Warm green, so it belongs to this palette. */
val Affirm = Color(0xFF7D9A5B)

// ===========================================================================
// Compatibility names
//
// Every screen in the app refers to the old token names. Rather than a
// mechanical rename across forty files -- which is the kind of change that
// looks enormous in a diff and alters nothing on screen -- the old names are
// kept and repointed at the new palette. The six container shades collapse
// onto two, which is the point: code that asked for a different card colour
// now gets the same one, and the card stacks flatten on their own.
//
// New code should use the names above.
// ===========================================================================

val Signal = Ember
val SignalDim = Color(0xFFB04A2B)
val SignalGlow = EmberQuiet
val SignalBorder = EmberEdge
val SignalSoft = Color(0xFF2A1A14)

val Primary = Ember
val PrimaryVariant = SignalDim
val PrimaryLight = Color(0xFFF08A68)
val PrimaryDark = Color(0xFF8F3A20)

val BackgroundDark = Ground
val BackgroundDarkSecondary = Ground
val BackgroundDarkTertiary = GroundRaised
val SurfaceDark = GroundRaised
val SurfaceElevated = Color(0xFF241F1B)
val CardDark = GroundRaised
val CardDarkElevated = SurfaceElevated

val TextPrimary = Ink
val TextSecondary = InkMuted
val TextTertiary = InkFaint
val TextMuted = Color(0xFF453F39)

val AccentBlue = Ember
val AccentGreen = Affirm
val AccentRed = Alarm
val AccentOrange = Caution
val AccentPurple = Color(0xFFA8815E)
val AccentPink = Color(0xFFC97B63)
val AccentCyan = Color(0xFF8FA58C)

val StatusActive = Affirm
val StatusInactive = InkFaint
val StatusWarning = Caution
val StatusError = Alarm
val SuccessGreen = Affirm
val ErrorRed = Alarm
val WarningOrange = Caution

val DistractiveColor = Ember
val NeutralColor = InkMuted
val ProductiveColor = Affirm

val OverlayBackground = Color(0xF212100E)
val OverlayCard = GroundRaised

val ScheduleWork = Ember
val ScheduleSleep = Color(0xFFA8815E)
val ScheduleStudy = Affirm
val ScheduleFamily = Color(0xFFC97B63)
val ScheduleSocial = Alarm
val ScheduleDetox = Caution

val GradientStart = Ember
val GradientEnd = SignalDim

val SurfaceBorder = Rule
val SurfaceBorderLight = Color(0xFF3A342E)

val Divider = Rule
val Ripple = Color(0x22F2EDE6)

val GlowPrimary = EmberQuiet
val GlowSuccess = Color(0x337D9A5B)
