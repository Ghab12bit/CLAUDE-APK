package com.focusblock.app.ui.setup

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.ui.components.AppIconGrid
import com.focusblock.app.ui.components.GridApp
import com.focusblock.app.ui.permissions.PermissionFlow
import com.focusblock.app.ui.theme.*

/**
 * First-run setup.
 *
 * Fresh install to one routine that will fire tonight, in about a minute.
 * Three questions: may I, which apps, what time. No goal to type, no intention
 * to declare, no tour.
 */
@Composable
fun SetupScreen(
    viewModel: SetupViewModel = hiltViewModel(),
    onComplete: () -> Unit
) {
    val state by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    // Permissions are granted in system settings, so re-check on every return.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(state.message) {
        state.message?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.consumeMessage()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = BackgroundDark
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BackgroundDark)
        ) {
            // Permissions get the whole screen and their own pacing: they are a
            // guided walk through Android's settings, one at a time, and they
            // do not fit the question-and-Next rhythm of the rest of setup.
            if (state.step == SetupStep.PERMISSIONS) {
                PermissionFlow(
                    onFinished = viewModel::next,
                    onExit = null
                )
            } else {
                Column(Modifier.fillMaxSize().padding(24.dp)) {
                    StepDots(state.step)
                    Spacer(Modifier.height(24.dp))

                    Box(Modifier.weight(1f)) {
                        when (state.step) {
                            SetupStep.WHAT_IT_DOES -> WhatItDoes()
                            SetupStep.PERMISSIONS -> Unit
                            SetupStep.CHOOSE_APPS -> ChooseApps(state, viewModel)
                            SetupStep.EVENING_BLOCK -> EveningBlock(state, viewModel)
                            SetupStep.REASON -> Reason(state, viewModel)
                            SetupStep.DONE -> Done(state)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    NavRow(state, viewModel, onComplete)
                }
            }
        }
    }
}

@Composable
private fun StepDots(step: SetupStep) {
    val index = SetupStep.values().indexOf(step)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        SetupStep.values().forEachIndexed { i, _ ->
            Box(
                Modifier
                    .weight(1f)
                    .height(3.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (i <= index) Signal else SurfaceElevated)
            )
        }
    }
}

@Composable
private fun WhatItDoes() {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Text(
            "FocusBlock",
            color = TextPrimary,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(10.dp))
        Text(
            "It blocks the apps you choose, at the times you choose.",
            color = TextPrimary,
            fontSize = 17.sp
        )
        Spacer(Modifier.height(20.dp))
        Text(
            "That's the whole idea. When a blocked app is open, you get a " +
                "screen telling you which rule is blocking it and when it lifts.",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(14.dp))
        Text(
            "You'll set up one routine now — a protected window in the evening. " +
                "You can change or delete it any time.",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(20.dp))
        // Said plainly at the start rather than implied by a marketing line.
        Text(
            "It won't rewire anything or fix your attention span. It makes the " +
                "apps harder to reach so the decision isn't yours at 9pm.",
            color = TextTertiary,
            fontSize = 13.sp
        )
    }
}

@Composable
private fun ChooseApps(state: SetupUiState, viewModel: SetupViewModel) {
    Column {
        Heading("What pulls you in?")
        Text(
            if (state.usageDataAvailable)
                "Ticked from your actual usage over the last week. Change anything."
            else
                "Usage access is off, so these are common ones. Change anything.",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "${state.selectedPackages.size} selected",
            color = Primary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium
        )
        Spacer(Modifier.height(12.dp))

        if (state.appsLoading) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Signal, modifier = Modifier.size(26.dp))
            }
        } else {
            // A grid of real icons, not a column of checkboxes. The user
            // recognises their own apps instantly; a list makes them read.
            Box(Modifier.verticalScroll(rememberScrollState())) {
                AppIconGrid(
                    apps = state.apps.map {
                        GridApp(
                            packageName = it.packageName,
                            label = it.label,
                            selected = it.selected,
                            essential = it.essential,
                            caption = if (it.minutesPerDay > 0) "${it.minutesPerDay}m/day" else ""
                        )
                    },
                    onToggle = viewModel::toggleApp,
                    columns = 4
                )
            }
        }
    }
}

@Composable
private fun EveningBlock(state: SetupUiState, viewModel: SetupViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("Your evening block")
        Text(
            "Starts after you're home and fed, ends before bed. " +
                "The gap is deliberate — adjust it to when you actually start.",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(18.dp))

        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TimeBox("Starts", state.startMinute, Modifier.weight(1f)) {
                viewModel.setTimes(it, state.endMinute)
            }
            TimeBox("Ends", state.endMinute, Modifier.weight(1f)) {
                viewModel.setTimes(state.startMinute, it)
            }
        }

        Spacer(Modifier.height(18.dp))
        Text("Days", color = TextSecondary, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        DayRow(state.daysOfWeek, viewModel::setDays)

        Spacer(Modifier.height(20.dp))
        Text(
            "How hard to stop",
            color = TextSecondary,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        CommitmentChoice(state.commitment, viewModel::setCommitment)
    }
}

@Composable
private fun CommitmentChoice(current: CommitmentLevel, onChange: (CommitmentLevel) -> Unit) {
    // Terms are on screen before it arms. The app never raises this by itself.
    val options = listOf(
        Triple(CommitmentLevel.OFF, "Off", "Stop it whenever you want."),
        Triple(
            CommitmentLevel.LOCKED,
            "Locked (suggested)",
            "You can't stop it early. One emergency unlock a day, 15 minutes, recorded. " +
                "Your allowed apps stay reachable the whole time."
        ),
        Triple(
            CommitmentLevel.PIN_LOCKED,
            "PIN locked",
            "Needs a PIN and a short wait before it will stop."
        )
    )
    Column {
        options.forEach { (level, title, terms) ->
            val on = current == level
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) SignalGlow else SurfaceDark)
                    .then(
                        if (on) Modifier.border(1.dp, SignalBorder, RoundedCornerShape(14.dp))
                        else Modifier
                    )
                    .clickable { onChange(level) }
                    .padding(14.dp)
            ) {
                Text(
                    title,
                    color = if (on) Signal else TextPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(Modifier.height(3.dp))
                Text(terms, color = TextSecondary, fontSize = 12.sp)
            }
        }
    }
}


/**
 * The one question worth asking, asked once.
 *
 * At 9pm the urge to scroll is concrete and "get clients" is a word. This
 * captures the user's own phrasing so the block screen can put it back in front
 * of them at the exact moment the urge is winning. It is asked HERE and never
 * again -- there is no nightly prompt, no reflection, no log. Skipping it is a
 * first-class option and the blocker works identically without it.
 */
@Composable
private fun Reason(state: SetupUiState, viewModel: SetupViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("What's this time for?")
        Text(
            "When you reach for Instagram at 9pm, the urge is specific and your " +
                "reason is vague. This puts your reason where the urge is.",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(20.dp))

        OutlinedTextField(
            value = state.reason,
            onValueChange = viewModel::setReason,
            placeholder = { Text("Send 3 client messages", color = TextTertiary) },
            singleLine = false,
            minLines = 2,
            textStyle = androidx.compose.ui.text.TextStyle(
                color = TextPrimary,
                fontSize = 17.sp,
                fontWeight = FontWeight.Medium
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(16.dp))
        Text(
            "Written once. You'll never be asked again.",
            color = TextTertiary,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(24.dp))
        Text("Or start from one of these", color = TextSecondary, fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        REASON_SUGGESTIONS.forEach { suggestion ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(SurfaceDark)
                    .clickable { viewModel.setReason(suggestion) }
                    .padding(14.dp)
            ) {
                Text(suggestion, color = TextPrimary, fontSize = 14.sp)
            }
        }
    }
}

private val REASON_SUGGESTIONS = listOf(
    "Send 3 client messages",
    "Ship one thing I can show someone",
    "Work on my own business, not someone else's feed",
    "Two hours that belong to me"
)

@Composable
private fun Done(state: SetupUiState) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Filled.CheckCircle,
            contentDescription = null,
            tint = Signal,
            modifier = Modifier.size(52.dp)
        )
        Spacer(Modifier.height(18.dp))
        Text("You're set", color = TextPrimary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(10.dp))
        Text(
            "${state.selectedPackages.size} apps will be blocked from " +
                "${formatClock(state.startMinute)} to ${formatClock(state.endMinute)}.",
            color = TextSecondary,
            fontSize = 14.sp,
            textAlign = TextAlign.Center
        )
        if (!state.canEnforce) {
            Spacer(Modifier.height(14.dp))
            Text(
                "Permissions are still off, so nothing will be blocked yet. " +
                    "The home screen will show you what's missing.",
                color = AccentOrange,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun NavRow(state: SetupUiState, viewModel: SetupViewModel, onComplete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (state.step != SetupStep.WHAT_IT_DOES && state.step != SetupStep.DONE) {
            OutlinedButton(
                onClick = viewModel::back,
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextSecondary)
            ) {
                Text("Back")
            }
        }

        Button(
            onClick = {
                when (state.step) {
                    SetupStep.REASON -> viewModel.finish { viewModel.next() }
                    SetupStep.DONE -> onComplete()
                    else -> viewModel.next()
                }
            },
            enabled = !state.saving,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Signal),
            modifier = Modifier.weight(1f).height(50.dp)
        ) {
            Text(
                when (state.step) {
                    SetupStep.WHAT_IT_DOES -> "Get started"
                    SetupStep.PERMISSIONS -> if (state.canEnforce) "Next" else "Continue anyway"
                    SetupStep.CHOOSE_APPS -> "Next"
                    SetupStep.EVENING_BLOCK -> "Next"
                    SetupStep.REASON ->
                        if (state.saving) "Saving..."
                        else if (state.reason.isBlank()) "Skip for now"
                        else "Create routine"
                    SetupStep.DONE -> "Open FocusBlock"
                },
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ---------------------------------------------------------------------------

@Composable
private fun Heading(text: String) {
    Text(text, color = TextPrimary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun TimeBox(label: String, minuteOfDay: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .padding(12.dp)
    ) {
        Text(label, color = TextTertiary, fontSize = 11.sp)
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Step("-") { onChange(((minuteOfDay - 15) + 1440) % 1440) }
            Text(
                formatClock(minuteOfDay),
                color = TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center
            )
            Step("+") { onChange((minuteOfDay + 15) % 1440) }
        }
    }
}

@Composable
private fun Step(label: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(SurfaceElevated)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = TextPrimary, fontSize = 17.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun DayRow(csv: String, onChange: (String) -> Unit) {
    val selected = csv.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DAY_LABELS.forEachIndexed { index, label ->
            val day = index + 1
            val on = day in selected
            Box(
                Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (on) SignalGlow else SurfaceDark)
                    .clickable {
                        val next = selected.toMutableSet()
                        if (!next.add(day)) next.remove(day)
                        onChange(next.sorted().joinToString(","))
                    },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    label,
                    color = if (on) Signal else TextTertiary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

private fun formatClock(minuteOfDay: Int): String {
    val h24 = minuteOfDay / 60
    val m = minuteOfDay % 60
    val suffix = if (h24 < 12) "am" else "pm"
    val h = when {
        h24 == 0 -> 12
        h24 > 12 -> h24 - 12
        else -> h24
    }
    return String.format("%d:%02d%s", h, m, suffix)
}

private val DAY_LABELS = listOf("M", "T", "W", "T", "F", "S", "S")
