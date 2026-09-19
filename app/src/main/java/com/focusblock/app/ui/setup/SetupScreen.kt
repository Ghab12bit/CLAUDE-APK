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
import androidx.compose.material.icons.filled.Warning
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
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .background(BackgroundDark)
                .padding(24.dp)
        ) {
            StepDots(state.step)
            Spacer(Modifier.height(24.dp))

            Box(Modifier.weight(1f)) {
                when (state.step) {
                    SetupStep.WHAT_IT_DOES -> WhatItDoes()
                    SetupStep.PERMISSIONS -> Permissions(state, viewModel)
                    SetupStep.CHOOSE_APPS -> ChooseApps(state, viewModel)
                    SetupStep.EVENING_BLOCK -> EveningBlock(state, viewModel)
                    SetupStep.DONE -> Done(state)
                }
            }

            Spacer(Modifier.height(16.dp))
            NavRow(state, viewModel, onComplete)
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
private fun Permissions(state: SetupUiState, viewModel: SetupViewModel) {
    Column(Modifier.verticalScroll(rememberScrollState())) {
        Heading("Two permissions")
        Text(
            "Android only lets an app block other apps if you allow these. " +
                "Without them this app can't do its job, and it will say so " +
                "on the home screen rather than pretend.",
            color = TextSecondary,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(20.dp))

        PermissionRow(
            title = "Accessibility service",
            why = "Lets FocusBlock see which app just opened. This is what does the blocking.",
            granted = state.hasAccessibility,
            onGrant = viewModel::openAccessibilitySettings
        )
        PermissionRow(
            title = "Usage access",
            why = "Lets it count time for daily limits and show your screen time.",
            granted = state.hasUsageAccess,
            onGrant = viewModel::openUsageAccessSettings
        )
        PermissionRow(
            title = "Display over apps",
            why = "Lets the block screen appear reliably. Recommended.",
            granted = state.hasOverlay,
            onGrant = viewModel::openOverlaySettings
        )

        if (!state.canEnforce) {
            Spacer(Modifier.height(10.dp))
            Text(
                "You can continue without these, but nothing will be blocked " +
                    "until they're on.",
                color = AccentOrange,
                fontSize = 13.sp
            )
        }
    }
}

@Composable
private fun PermissionRow(title: String, why: String, granted: Boolean, onGrant: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceDark)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (granted) Icons.Filled.CheckCircle else Icons.Filled.Warning,
                contentDescription = null,
                tint = if (granted) Signal else AccentOrange,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(title, color = TextPrimary, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        Text(why, color = TextSecondary, fontSize = 13.sp)
        if (!granted) {
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onGrant,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Signal)
            ) {
                Text("Turn on", fontSize = 13.sp)
            }
        }
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
            Column(Modifier.verticalScroll(rememberScrollState())) {
                state.apps.forEach { app ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleApp(app.packageName) }
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = app.selected,
                            onCheckedChange = { viewModel.toggleApp(app.packageName) },
                            colors = CheckboxDefaults.colors(checkedColor = Signal)
                        )
                        Spacer(Modifier.width(6.dp))
                        Column(Modifier.weight(1f)) {
                            Text(app.label, color = TextPrimary, fontSize = 14.sp)
                            when {
                                // Named, never silently excluded. The user decides
                                // whether a messaging app is a distraction; the app
                                // does not decide for them.
                                app.essential -> Text(
                                    "You may need this for calls or clients",
                                    color = AccentOrange,
                                    fontSize = 11.sp
                                )
                                app.minutesPerDay > 0 -> Text(
                                    "${app.minutesPerDay}m a day",
                                    color = TextTertiary,
                                    fontSize = 11.sp
                                )
                            }
                        }
                    }
                }
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
                    SetupStep.EVENING_BLOCK -> viewModel.finish { viewModel.next() }
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
                    SetupStep.EVENING_BLOCK -> if (state.saving) "Saving..." else "Create routine"
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
