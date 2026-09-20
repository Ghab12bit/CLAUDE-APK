package com.focusblock.app.ui.permissions

import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.PermissionUtils
import kotlinx.coroutines.delay

/**
 * Permissions as one guided flow, not a list of five taps.
 *
 * The screen this replaces showed five rows, each with a "Turn on" button that
 * threw the user at a different system screen with no idea what to look for
 * once they arrived. Android's own screens are the hard part: "Usage access"
 * is buried three levels into Special app access, and the accessibility toggle
 * raises a full-screen warning about full device control that reads like a
 * malware prompt. A list cannot help with any of that.
 *
 * So: one permission per screen, each with the exact path through Settings,
 * a drawing of the toggle being flipped, and a warning about the dialog that
 * is about to appear. The flow then watches for the grant itself -- when the
 * user comes back, it checks, confirms, and moves on without another tap.
 */

// ---------------------------------------------------------------------------
// What we ask for
// ---------------------------------------------------------------------------

enum class PermissionId { ACCESSIBILITY, USAGE, OVERLAY, BATTERY, NOTIFICATIONS, EXACT_ALARM }

/**
 * One request.
 *
 * [promise] is deliberately about the user's evening, not about Android: the
 * reason to grant a permission is what it buys, not what it is called.
 */
data class PermissionStep(
    val id: PermissionId,
    val title: String,
    val promise: String,
    val detail: String,
    /** Where it lives in Settings, as breadcrumbs. Empty for a system dialog. */
    val path: List<String>,
    /** The row label to look for once there. */
    val rowLabel: String = "FocusBlock",
    val required: Boolean,
    /** Shown in red before the tap, when Android is about to alarm the user. */
    val headsUp: String = "",
    val isGranted: (Context) -> Boolean,
    val open: (Context) -> Unit
)

/**
 * The steps that apply to this device, in the order they matter.
 *
 * Accessibility first because nothing works without it, and because a user who
 * abandons the flow after one grant should have granted the one that counts.
 */
fun permissionSteps(): List<PermissionStep> = buildList {
    add(
        PermissionStep(
            id = PermissionId.ACCESSIBILITY,
            title = "Let FocusBlock see which app opens",
            promise = "This is the one that does the blocking.",
            detail = "Android tells accessibility services which app just came to " +
                "the front. That signal is the whole mechanism -- without it, " +
                "FocusBlock cannot tell that you opened Instagram, so it cannot " +
                "stop you.",
            path = listOf("Accessibility", "Downloaded apps", "FocusBlock"),
            required = true,
            headsUp = "Android will warn you about \"full control of your device\". " +
                "That warning is shown for every accessibility service. FocusBlock " +
                "uses the signal to know which app is open, and nothing else -- it " +
                "reads no content and sends nothing anywhere.",
            isGranted = { PermissionUtils.hasAccessibilityServiceEnabled(it) },
            open = { PermissionUtils.openAccessibilitySettings(it) }
        )
    )
    add(
        PermissionStep(
            id = PermissionId.USAGE,
            title = "Let it count your screen time",
            promise = "Daily limits and your real usage figures.",
            detail = "Needed to measure how long you have spent in an app today, " +
                "which is what a daily budget counts down. It also lets setup tick " +
                "your actual time sinks instead of guessing.",
            path = listOf("Apps", "Special app access", "Usage access", "FocusBlock"),
            required = true,
            isGranted = { PermissionUtils.hasUsageStatsPermission(it) },
            open = { PermissionUtils.openUsageAccessSettings(it) }
        )
    )
    add(
        PermissionStep(
            id = PermissionId.OVERLAY,
            title = "Let the block screen appear",
            promise = "The pause shows up over the app you opened.",
            detail = "Without this the block screen has to launch as a separate " +
                "activity, which some versions of Android delay or drop. With it, " +
                "the pause appears immediately, every time.",
            path = listOf("Apps", "Special app access", "Display over other apps", "FocusBlock"),
            required = true,
            isGranted = { PermissionUtils.hasOverlayPermission(it) },
            open = { PermissionUtils.openOverlaySettings(it) }
        )
    )
    add(
        PermissionStep(
            id = PermissionId.BATTERY,
            title = "Stop Android killing it overnight",
            promise = "The usual reason a blocker quietly stops working.",
            detail = "Battery optimisation suspends background work. For most apps " +
                "that is fine. For this one it means the 8:45pm routine never " +
                "starts, and you find out by scrolling through it.",
            path = emptyList(),
            required = false,
            isGranted = { PermissionUtils.isIgnoringBatteryOptimizations(it) },
            open = { PermissionUtils.requestIgnoreBatteryOptimizations(it) }
        )
    )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        add(
            PermissionStep(
                id = PermissionId.EXACT_ALARM,
                title = "Let routines start on the minute",
                promise = "8:45 means 8:45, not somewhere around then.",
                detail = "Android 12 and later batch alarms to save battery. " +
                    "Without this a routine can open or lift up to a minute late.",
                path = emptyList(),
                required = false,
                isGranted = { PermissionUtils.canScheduleExactAlarms(it) },
                open = { PermissionUtils.openExactAlarmSettings(it) }
            )
        )
    }
    add(
        PermissionStep(
            id = PermissionId.NOTIFICATIONS,
            title = "Let it show what's running",
            promise = "One quiet notification while a routine is on.",
            detail = "The ongoing notification is also how Android keeps the " +
                "blocking service alive. Turning it off makes the service easier " +
                "for the system to kill.",
            path = emptyList(),
            required = false,
            isGranted = { PermissionUtils.hasNotificationPermission(it) },
            open = { PermissionUtils.openNotificationSettings(it) }
        )
    )
}

// ---------------------------------------------------------------------------
// The flow
// ---------------------------------------------------------------------------

/**
 * Walks the user through [steps] one screen at a time.
 *
 * [onFinished] fires when the last step is passed or skipped. [onExit] is the
 * back door -- setup passes its own "continue anyway", settings passes close.
 */
@Composable
fun PermissionFlow(
    onFinished: () -> Unit,
    onExit: (() -> Unit)? = null,
    /** Jump straight to one permission, for a "fix this" tap in settings. */
    startAt: PermissionId? = null,
    steps: List<PermissionStep> = remember { permissionSteps() }
) {
    val context = LocalContext.current
    var index by remember {
        mutableStateOf(steps.indexOfFirst { it.id == startAt }.coerceAtLeast(0))
    }
    var granted by remember { mutableStateOf(steps.map { it.isGranted(context) }) }
    var justGranted by remember { mutableStateOf(false) }

    // Android 13+ takes notifications through a runtime dialog rather than a
    // settings screen, so that one step asks in place.
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted = steps.map { it.isGranted(context) } }

    fun advance() {
        justGranted = false
        val next = (index + 1 until steps.size).firstOrNull { !granted[it] }
        if (next == null) onFinished() else index = next
    }

    // The grant happens outside this app, so the only moment we can learn about
    // it is the return. Re-check everything then, and if the step the user just
    // left is now on, say so and move them along without another tap.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, index) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val fresh = steps.map { it.isGranted(context) }
                val nowOn = !granted.getOrElse(index) { false } && fresh.getOrElse(index) { false }
                granted = fresh
                if (nowOn) justGranted = true
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Hold the confirmation long enough to be read, then carry on.
    LaunchedEffect(justGranted, index) {
        if (justGranted) {
            delay(900)
            advance()
        }
    }

    // Nothing left to guide. Skipped when the caller asked for one specific
    // permission: they came here to look at it, granted or not.
    LaunchedEffect(granted) {
        if (startAt == null && granted.isNotEmpty() && granted.all { it }) onFinished()
    }

    val step = steps.getOrNull(index) ?: return
    val isOn = granted.getOrElse(index) { false }

    Column(
        Modifier
            .fillMaxSize()
            .background(BackgroundDark)
            .padding(horizontal = 24.dp)
            .padding(top = 20.dp, bottom = 24.dp)
    ) {
        ProgressSegments(steps.size, index, granted)

        Spacer(Modifier.height(20.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Step ${index + 1} of ${steps.size}",
                color = TextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (step.required) "Required" else "Recommended",
                color = if (step.required) Signal else TextTertiary,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium
            )
        }

        Spacer(Modifier.height(10.dp))

        Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
            Text(
                step.title,
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                lineHeight = 32.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(step.promise, color = Signal, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            Spacer(Modifier.height(20.dp))

            // The drawing: what the user is about to be looking at.
            ToggleDiagram(
                rowLabel = step.rowLabel,
                path = step.path,
                settled = isOn
            )

            Spacer(Modifier.height(20.dp))
            Text(step.detail, color = TextSecondary, fontSize = 14.sp, lineHeight = 21.sp)

            if (step.headsUp.isNotBlank() && !isOn) {
                Spacer(Modifier.height(16.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(WarnGlow)
                        .border(1.dp, WarnBorder, RoundedCornerShape(14.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        "Before you tap",
                        color = AccentOrange,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(step.headsUp, color = TextSecondary, fontSize = 13.sp, lineHeight = 19.sp)
                }
            }
        }

        AnimatedVisibility(visible = justGranted, enter = fadeIn(), exit = fadeOut()) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    Modifier.size(18.dp).clip(CircleShape).background(Signal),
                    contentAlignment = Alignment.Center
                ) { Text("✓", color = Ground, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
                Spacer(Modifier.width(8.dp))
                Text("That one's on.", color = Signal, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            }
        }

        Button(
            onClick = {
                when {
                    isOn -> advance()
                    step.id == PermissionId.NOTIFICATIONS &&
                        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else -> step.open(context)
                }
            },
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Signal),
            modifier = Modifier.fillMaxWidth().height(52.dp)
        ) {
            Text(
                if (isOn) "Already on — next" else "Open settings",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center
        ) {
            // A required permission can still be deferred -- refusing to let the
            // user past a wall is how a setup flow gets uninstalled -- but the
            // wording says plainly what it costs.
            TextButton(onClick = { if (index == steps.lastIndex) onFinished() else advance() }) {
                Text(
                    if (step.required) "Skip — blocking won't work" else "Not now",
                    color = if (step.required) AccentOrange else TextTertiary,
                    fontSize = 13.sp
                )
            }
            if (onExit != null) {
                TextButton(onClick = onExit) {
                    Text("Close", color = TextTertiary, fontSize = 13.sp)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Pieces
// ---------------------------------------------------------------------------

/** Progress as filled segments: done, here, still to come. */
@Composable
private fun ProgressSegments(total: Int, current: Int, granted: List<Boolean>) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        repeat(total) { i ->
            val color = when {
                granted.getOrElse(i) { false } -> Signal
                i == current -> SignalDim
                else -> SurfaceElevated
            }
            Box(
                Modifier
                    .weight(1f)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(color)
            )
        }
    }
}

/**
 * A drawing of the Android settings row the user is about to hunt for, with the
 * toggle flipping on a loop.
 *
 * This is the part a list of buttons cannot do. The user is about to land on a
 * dense system screen; showing them the shape of the row they are looking for,
 * and the path that gets there, is worth more than any amount of prose.
 */
@Composable
private fun ToggleDiagram(rowLabel: String, path: List<String>, settled: Boolean) {
    // Loop the flip until it is actually on, then hold it on.
    var flipped by remember { mutableStateOf(false) }
    LaunchedEffect(settled) {
        if (settled) { flipped = true; return@LaunchedEffect }
        while (true) {
            delay(1100); flipped = true
            delay(1400); flipped = false
        }
    }
    val on = settled || flipped
    val knobOffset by animateDpAsState(if (on) 18.dp else 0.dp, tween(280), label = "knob")
    val trackColor by animateColorAsState(
        if (on) Signal else SurfaceElevated, tween(280), label = "track"
    )

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(SurfaceDark)
            .padding(16.dp)
    ) {
        if (path.isNotEmpty()) {
            Text("Where to find it", color = TextTertiary, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            // Breadcrumbs wrap, because "Display over other apps" is long and a
            // truncated path helps nobody.
            var line = "Settings"
            path.forEach { line += "  ›  $it" }
            Text(line, color = TextSecondary, fontSize = 13.sp, lineHeight = 20.sp)
            Spacer(Modifier.height(14.dp))
            Text("Then flip this", color = TextTertiary, fontSize = 11.sp)
        } else {
            Text("Android will ask you directly", color = TextTertiary, fontSize = 11.sp)
            Spacer(Modifier.height(4.dp))
            Text("One tap, no hunting.", color = TextSecondary, fontSize = 13.sp)
            Spacer(Modifier.height(14.dp))
        }

        Spacer(Modifier.height(8.dp))

        // The mock row, drawn to look like a system list item rather than like
        // this app, so it is recognisable when the real one appears.
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(CardDark)
                .border(1.dp, SurfaceBorder, RoundedCornerShape(12.dp))
                .padding(horizontal = 14.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                Modifier.size(30.dp).clip(RoundedCornerShape(8.dp)).background(SignalGlow),
                contentAlignment = Alignment.Center
            ) {
                Text("F", color = Signal, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(rowLabel, color = TextPrimary, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(
                    if (on) "Allowed" else "Not allowed",
                    color = if (on) Signal else TextTertiary,
                    fontSize = 12.sp
                )
            }
            Box(
                Modifier
                    .width(44.dp)
                    .height(26.dp)
                    .clip(RoundedCornerShape(13.dp))
                    .background(trackColor)
                    .padding(3.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                Box(
                    Modifier
                        .offset(x = knobOffset)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(TextPrimary)
                )
            }
        }
    }
}

// The one pair of colours this screen needs that the palette does not carry:
// a warning tint for the accessibility heads-up.
private val WarnGlow = androidx.compose.ui.graphics.Color(0x1FF0883E)
private val WarnBorder = androidx.compose.ui.graphics.Color(0x59F0883E)
