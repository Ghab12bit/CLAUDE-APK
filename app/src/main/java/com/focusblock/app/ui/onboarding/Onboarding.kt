package com.focusblock.app.ui.onboarding

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.focusblock.app.R
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.BlockSetup
import com.focusblock.app.core.HealthState
import com.focusblock.app.core.PermissionHealth
import com.focusblock.app.core.Requirement
import com.focusblock.app.core.StartRequest
import com.focusblock.app.core.csv
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.policy.SessionType
import com.focusblock.app.ui.components.AppIconRow
import com.focusblock.app.ui.components.FbDivider
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.SecondaryButton
import com.focusblock.app.ui.components.SectionGap
import com.focusblock.app.ui.components.TextLink
import com.focusblock.app.ui.picker.AppPickerSheet
import com.focusblock.app.ui.picker.PickerContext
import com.focusblock.app.ui.settings.RefreshOnResume
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType
import kotlinx.coroutines.launch

private enum class Step { INTRO, APPS, ACCESSIBILITY, USAGE, NOTIFICATIONS, BATTERY, EXACT_ALARMS, TEST, READY }

/**
 * First run (spec 4.10): what FocusBlock does, pick apps, permissions one at a time, a 1-minute
 * test block, then "FocusBlock is ready". Skipping is allowed; the Block tab then names the missing
 * permission and disables Start with a reason.
 */
@Composable
fun OnboardingFlow(onDone: () -> Unit) {
    val context = LocalContext.current
    val graph = remember { AppGraph.get(context.applicationContext) }
    val scope = rememberCoroutineScope()
    var stepIndex by rememberSaveable { mutableIntStateOf(0) }
    var appsCsv by rememberSaveable { mutableStateOf("") }
    var picker by rememberSaveable { mutableStateOf(false) }
    var testStartedAt by rememberSaveable { mutableLongStateOf(0L) }
    var testId by rememberSaveable { mutableLongStateOf(0L) }
    var testSeen by rememberSaveable { mutableStateOf(false) }
    var refreshTick by rememberSaveable { mutableIntStateOf(0) }
    var startingTest by remember { mutableStateOf(false) }
    RefreshOnResume { refreshTick++ }

    val steps = Step.values().filter { it != Step.EXACT_ALARMS || Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val step = steps[stepIndex.coerceIn(0, steps.lastIndex)]
    // A test block ends quietly: it never asks "Did you finish?" and never replaces the apps chosen
    // above as the Block tab's selection.
    fun endTest(id: Long) = scope.launch {
        graph.sessions.discardTest(id)
        graph.notifier.cancelEnded()
    }
    // Leaving the test step (continue, skip or back) ends a running test block.
    fun leaveTest() {
        if (step != Step.TEST) return
        if (testId > 0) {
            endTest(testId)
            testId = 0
        }
        // Coming back offers a new test rather than waiting on the one just ended.
        if (!testSeen) testStartedAt = 0
    }
    fun next() {
        leaveTest()
        stepIndex = (stepIndex + 1).coerceAtMost(steps.lastIndex)
    }
    // Back goes to the previous step instead of closing the app and starting over.
    BackHandler(enabled = stepIndex > 0) {
        leaveTest()
        stepIndex = (stepIndex - 1).coerceAtLeast(0)
    }
    val apps = csv(appsCsv)

    // Permission steps advance on their own once granted (checked on every resume).
    val requirement = when (step) {
        Step.ACCESSIBILITY -> Requirement.ACCESSIBILITY
        Step.USAGE -> Requirement.USAGE
        Step.NOTIFICATIONS -> Requirement.NOTIFICATIONS
        Step.BATTERY -> Requirement.BATTERY
        Step.EXACT_ALARMS -> Requirement.EXACT_ALARMS
        else -> null
    }
    val granted = remember(refreshTick, step) { requirement != null && PermissionHealth.state(context, requirement) == HealthState.OK }
    fun rationale() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        (context as? Activity)?.shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS) == true
    // Rationale state when the request was made: true before or after means the dialog was shown.
    var rationaleBefore by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        refreshTick++
        // Once Android stops showing the dialog (denied twice, or turned off in system settings), the
        // request comes back denied at once; the switch is then only in the app's notification settings.
        // A dialog the user just declined (first or second time) is left as their answer.
        val justDeclined = !allowed && (rationaleBefore || rationale())
        if (!justDeclined && PermissionHealth.state(context, Requirement.NOTIFICATIONS) != HealthState.OK) PermissionHealth.open(context, Requirement.NOTIFICATIONS)
    }

    if (step == Step.TEST && testStartedAt > 0 && !testSeen) {
        // The intervention logs a blocked attempt; seeing it means blocking works end to end.
        androidx.compose.runtime.LaunchedEffect(refreshTick) {
            val app = apps.firstOrNull() ?: return@LaunchedEffect
            if (graph.db.attemptDao().countSince(app, testStartedAt) > 0) testSeen = true
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(vertical = 24.dp)) {
        Image(painterResource(R.drawable.ic_mark), contentDescription = null, modifier = Modifier.padding(horizontal = Fb.gutter).size(28.dp))
        Spacer(Modifier.height(20.dp))
        if (stepIndex in 2 until steps.size - 2) {
            Text(stringResource(R.string.onb_step, stepIndex - 1, steps.size - 4), style = FbType.caption, modifier = Modifier.padding(horizontal = Fb.gutter))
            Spacer(Modifier.height(6.dp))
        }
        when (step) {
            Step.INTRO -> {
                Title(stringResource(R.string.app_name))
                Body(stringResource(R.string.onb_intro_1))
                Spacer(Modifier.height(8.dp))
                Body(stringResource(R.string.onb_intro_2))
                Actions { PrimaryButton(stringResource(R.string.onb_get_started), { next() }) }
            }
            Step.APPS -> {
                Title(stringResource(R.string.picker_title_onboarding))
                Body(stringResource(R.string.onb_pick_body))
                SectionGap()
                if (apps.isNotEmpty()) AppIconRow(apps, { graph.apps.label(it) }, onMore = { picker = true })
                Actions {
                    SecondaryButton(stringResource(R.string.editor_choose_apps), { picker = true })
                    PrimaryButton(stringResource(R.string.action_continue), {
                        scope.launch { if (apps.isNotEmpty()) graph.sessions.saveSelection(apps) }
                        next()
                    }, enabled = apps.isNotEmpty())
                    TextLink(stringResource(R.string.onb_skip), { next() }, accent = false)
                }
            }
            Step.ACCESSIBILITY -> PermissionStep(R.string.onb_acc_title, R.string.onb_acc_why, R.string.onb_acc_sees, R.string.onb_acc_not, granted,
                hint = R.string.onb_acc_hint, onAllow = { PermissionHealth.open(context, Requirement.ACCESSIBILITY) }, onNext = { next() })
            Step.USAGE -> PermissionStep(R.string.onb_usage_title, R.string.onb_usage_why, R.string.onb_usage_sees, R.string.onb_usage_not, granted,
                onAllow = { PermissionHealth.open(context, Requirement.USAGE) }, onNext = { next() })
            Step.NOTIFICATIONS -> PermissionStep(R.string.onb_notif_title, R.string.onb_notif_why, R.string.onb_notif_sees, R.string.onb_notif_not, granted,
                onAllow = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        rationaleBefore = rationale()
                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else PermissionHealth.open(context, Requirement.NOTIFICATIONS)
                }, onNext = { next() })
            Step.BATTERY -> PermissionStep(R.string.onb_battery_title, R.string.onb_battery_why, R.string.onb_battery_sees, R.string.onb_battery_not, granted,
                onAllow = { PermissionHealth.open(context, Requirement.BATTERY) }, onNext = { next() })
            Step.EXACT_ALARMS -> PermissionStep(R.string.onb_alarm_title, R.string.onb_alarm_why, R.string.onb_alarm_sees, R.string.onb_alarm_not, granted,
                onAllow = { PermissionHealth.open(context, Requirement.EXACT_ALARMS) }, onNext = { next() })
            Step.TEST -> {
                val app = apps.firstOrNull()
                val appName = app?.let { graph.apps.label(it) }.orEmpty()
                val ready = PermissionHealth.missingRequired(context) == null
                Title(stringResource(R.string.onb_test_title))
                when {
                    app == null || !ready -> {
                        Body(stringResource(if (app == null) R.string.onb_test_no_apps else R.string.onb_test_needs_accessibility))
                        Actions { PrimaryButton(stringResource(R.string.action_continue), { next() }) }
                    }
                    testSeen -> {
                        Body(stringResource(R.string.onb_test_seen))
                        Actions { PrimaryButton(stringResource(R.string.action_continue), { next() }) }
                    }
                    testStartedAt > 0 -> {
                        Body(stringResource(R.string.onb_test_waiting, appName))
                        Actions {
                            PrimaryButton(stringResource(R.string.onb_test_open, appName), {
                                context.packageManager.getLaunchIntentForPackage(app)?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                            })
                            TextLink(stringResource(R.string.onb_test_skip), { next() }, accent = false)
                        }
                    }
                    else -> {
                        Body(stringResource(R.string.onb_test_body, appName))
                        Actions {
                            PrimaryButton(stringResource(R.string.onb_test_start), {
                                // A second tap would fail as already running and skip the test.
                                if (!startingTest) {
                                    startingTest = true
                                    scope.launch {
                                        val result = graph.sessions.start(StartRequest(BlockSetup(listOf(app), SessionType.TIMED, 1), test = true))
                                        startingTest = false
                                        val started = result as? com.focusblock.app.core.StartResult.Started
                                        if (steps.getOrNull(stepIndex) != Step.TEST) {
                                            // Left the step (Back) while it started: end it like any other left test.
                                            started?.let { endTest(it.id) }
                                        } else if (started != null) {
                                            testId = started.id
                                            testStartedAt = System.currentTimeMillis()
                                        } else next()
                                    }
                                }
                            })
                            TextLink(stringResource(R.string.onb_test_skip), { next() }, accent = false)
                        }
                    }
                }
            }
            Step.READY -> {
                Title(stringResource(R.string.onb_ready_title))
                Body(stringResource(R.string.onb_ready_body))
                Actions {
                    PrimaryButton(stringResource(R.string.onb_finish), {
                        scope.launch {
                            graph.db.settingsDao().insert(AppSettings(PrefKeys.ONBOARDING_DONE, "true"))
                            onDone()
                        }
                    })
                }
            }
        }
    }

    if (picker) {
        AppPickerSheet(PickerContext.ONBOARDING, apps, onDismiss = { picker = false }, onDone = { appsCsv = it.joinToString(","); picker = false })
    }
}

@Composable
private fun Title(text: String) {
    Text(text, style = FbType.title, modifier = Modifier.padding(horizontal = Fb.gutter).semantics { heading() })
    Spacer(Modifier.height(12.dp))
}

@Composable
private fun Body(text: String) = Text(text, style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))

@Composable
private fun Actions(content: @Composable () -> Unit) {
    Spacer(Modifier.height(28.dp))
    Column(Modifier.padding(horizontal = Fb.gutter), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
}

@Composable
private fun PermissionStep(title: Int, why: Int, sees: Int, notSees: Int, granted: Boolean, onAllow: () -> Unit, onNext: () -> Unit, hint: Int? = null) {
    Title(stringResource(title))
    Section(stringResource(R.string.onb_why), stringResource(why))
    Section(stringResource(R.string.onb_sees), stringResource(sees))
    Section(stringResource(R.string.onb_not_sees), stringResource(notSees))
    hint?.let { Spacer(Modifier.height(8.dp)); Body(stringResource(it)) }
    Actions {
        if (granted) {
            Text(stringResource(R.string.onb_granted), style = FbType.label.copy(color = Fb.success))
            PrimaryButton(stringResource(R.string.action_continue), onNext)
        } else {
            PrimaryButton(stringResource(R.string.onb_open_settings), onAllow)
            TextLink(stringResource(R.string.onb_skip), onNext, accent = false)
        }
    }
}

@Composable
private fun Section(label: String, text: String) {
    Spacer(Modifier.height(10.dp))
    FbDivider()
    Spacer(Modifier.height(10.dp))
    Text(label, style = FbType.label.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
    Spacer(Modifier.height(4.dp))
    Text(text, style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter))
}
