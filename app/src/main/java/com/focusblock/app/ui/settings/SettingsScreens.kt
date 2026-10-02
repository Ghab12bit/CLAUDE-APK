package com.focusblock.app.ui.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusblock.app.BuildConfig
import com.focusblock.app.R
import com.focusblock.app.core.Fmt
import com.focusblock.app.core.HealthState
import com.focusblock.app.core.Notifier
import com.focusblock.app.core.PermissionHealth
import com.focusblock.app.core.Requirement
import com.focusblock.app.core.csv
import com.focusblock.app.database.entity.AppGroup
import com.focusblock.app.policy.SessionType
import com.focusblock.app.policy.Strength
import com.focusblock.app.ui.components.AppIcon
import com.focusblock.app.ui.components.BackHeader
import com.focusblock.app.ui.components.ChipKind
import com.focusblock.app.ui.components.DividerRow
import com.focusblock.app.ui.components.EmptyState
import com.focusblock.app.ui.components.FbCard
import com.focusblock.app.ui.components.FbDivider
import com.focusblock.app.ui.components.FbSwitch
import com.focusblock.app.ui.components.LabeledField
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.SecondaryButton
import com.focusblock.app.ui.components.SectionGap
import com.focusblock.app.ui.components.SectionLabel
import com.focusblock.app.ui.components.SegmentedControl
import com.focusblock.app.ui.components.StateChip
import com.focusblock.app.ui.components.Stepper
import com.focusblock.app.ui.components.TextLink
import com.focusblock.app.ui.components.pluralRes
import com.focusblock.app.ui.picker.AppPickerSheet
import com.focusblock.app.ui.picker.PickerContext
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbType

object SettingsRoutes {
    const val HOME = "settings"
    const val HEALTH = "settings/health"
    const val ESSENTIALS = "settings/essentials"
    const val SETS = "settings/sets"
    const val DEFAULT = "settings/default"
    const val NOTIFICATIONS = "settings/notifications"
    const val WIDGET = "settings/widget"
    const val DATA = "settings/data"
    const val TROUBLESHOOTING = "settings/troubleshooting"
}

/** Re-reads permission state whenever the user comes back from a system settings screen. */
@Composable
fun RefreshOnResume(onResume: () -> Unit) {
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) onResume() }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
}

@Composable
private fun Page(title: String, onBack: () -> Unit, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        BackHeader(title, onBack)
        Spacer(Modifier.height(8.dp))
        content()
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun MessageDialog(state: SettingsUi, vm: SettingsViewModel) {
    state.message?.let { msg ->
        AlertDialog(
            onDismissRequest = vm::clearMessage, containerColor = Fb.surface,
            text = { Text(stringResource(msg), style = FbType.body) },
            confirmButton = { TextButton(onClick = vm::clearMessage) { Text(stringResource(R.string.action_close), color = Fb.accent) } },
        )
    }
}

/** Plain grouped list; no profile header, no edition card (spec 4.9). */
@Composable
fun SettingsHome(state: SettingsUi, vm: SettingsViewModel, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val context = LocalContext.current
    RefreshOnResume(vm::refresh)
    val problems = state.health.filter { it.requirement.required && it.state != HealthState.OK } +
        state.health.filter { !it.requirement.required && it.state != HealthState.OK && it.requirement != Requirement.OVERLAY }
    Page(stringResource(R.string.settings), onBack) {
        Spacer(Modifier.height(8.dp))
        FbCard(contentPadding = 0.dp) {
        DividerRow(
            title = stringResource(R.string.settings_blocking_health),
            subtitle = if (problems.isEmpty()) stringResource(R.string.health_all_ok)
            else stringResource(R.string.health_problems, problems.joinToString(", ") { context.getString(it.requirement.label) }),
            onClick = { onOpen(SettingsRoutes.HEALTH) },
        )
        FbDivider()
        DividerRow(title = stringResource(R.string.settings_essentials), subtitle = pluralRes(R.plurals.essentials_count, state.essentials.size), onClick = { onOpen(SettingsRoutes.ESSENTIALS) })
        FbDivider()
        DividerRow(title = stringResource(R.string.settings_saved_sets), value = state.sets.size.toString(), onClick = { onOpen(SettingsRoutes.SETS) })
        FbDivider()
        DividerRow(title = stringResource(R.string.settings_default_block), onClick = { onOpen(SettingsRoutes.DEFAULT) })
        FbDivider()
        DividerRow(title = stringResource(R.string.settings_notifications), onClick = { onOpen(SettingsRoutes.NOTIFICATIONS) })
        FbDivider()
        DividerRow(title = stringResource(R.string.settings_widget), onClick = { onOpen(SettingsRoutes.WIDGET) })
        FbDivider()
        DividerRow(title = stringResource(R.string.settings_data), onClick = { onOpen(SettingsRoutes.DATA) })
        FbDivider()
        DividerRow(title = stringResource(R.string.settings_troubleshooting), onClick = { onOpen(SettingsRoutes.TROUBLESHOOTING) })
        FbDivider()
        DividerRow(title = stringResource(R.string.settings_about), value = stringResource(R.string.about_version, BuildConfig.VERSION_NAME))
        }
    }
}

/** Blocking health: each permission/service with status and Fix (spec 4.9, 9.1). */
@Composable
fun HealthScreen(state: SettingsUi, vm: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    RefreshOnResume(vm::refresh)
    var repair by rememberSaveable { mutableStateOf(false) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { vm.refresh() }
    Page(stringResource(R.string.settings_blocking_health), onBack) {
        state.health.forEach { item ->
            FbDivider()
            val r = item.requirement
            val (kind, text) = when (item.state) {
                HealthState.OK -> ChipKind.ACTIVE to stringResource(R.string.health_ok)
                HealthState.NOT_RUNNING -> ChipKind.BROKEN to stringResource(R.string.health_not_running)
                HealthState.OFF -> (if (r.required) ChipKind.BROKEN else ChipKind.OFF) to
                    (if (r.required) stringResource(R.string.health_off) else stringResource(R.string.health_off) + " · " + stringResource(R.string.health_optional))
            }
            DividerRow(
                title = stringResource(r.label),
                subtitle = stringResource(r.description),
                trailing = {
                    Column {
                        StateChip(text, kind)
                        if (item.state != HealthState.OK) {
                            TextLink(stringResource(if (item.state == HealthState.NOT_RUNNING) R.string.health_repair else R.string.action_fix), {
                                when {
                                    item.state == HealthState.NOT_RUNNING -> repair = true
                                    r == Requirement.NOTIFICATIONS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                                        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else -> PermissionHealth.open(context, r)
                                }
                            })
                        }
                    }
                },
            )
        }
        FbDivider()
    }
    if (repair) {
        AlertDialog(
            onDismissRequest = { repair = false }, containerColor = Fb.surface,
            title = { Text(stringResource(R.string.status_not_running), style = FbType.heading) },
            text = { Text(stringResource(R.string.health_repair_body), style = FbType.body) },
            confirmButton = { TextButton(onClick = { repair = false; PermissionHealth.open(context, Requirement.ACCESSIBILITY) }) { Text(stringResource(R.string.onb_open_settings), color = Fb.accent) } },
            dismissButton = { TextButton(onClick = { repair = false }) { Text(stringResource(R.string.action_cancel), color = Fb.textSecondary) } },
        )
    }
}

@Composable
fun EssentialsScreen(state: SettingsUi, vm: SettingsViewModel, onBack: () -> Unit) {
    var picker by rememberSaveable { mutableStateOf(false) }
    Page(stringResource(R.string.settings_essentials), onBack) {
        Text(stringResource(R.string.essentials_body), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
        Spacer(Modifier.height(12.dp))
        if (state.strictActive) {
            Text(stringResource(R.string.essentials_locked), style = FbType.label.copy(color = Fb.warning), modifier = Modifier.padding(horizontal = Fb.gutter))
            Spacer(Modifier.height(12.dp))
        }
        Column(Modifier.padding(horizontal = Fb.gutter)) { SecondaryButton(stringResource(R.string.essentials_add), { picker = true }, enabled = !state.strictActive) }
        SectionGap()
        if (state.essentials.isEmpty()) EmptyState(stringResource(R.string.essentials_empty))
        state.essentials.sortedBy { vm.label(it).lowercase() }.forEach { pkg ->
            FbDivider()
            DividerRow(title = vm.label(pkg), leading = { AppIcon(pkg, null, 32.dp) })
        }
        FbDivider()
    }
    MessageDialog(state, vm)
    if (picker) {
        AppPickerSheet(PickerContext.ESSENTIALS, state.essentials.toList(), onDismiss = { picker = false }, onDone = { vm.setEssentials(it); picker = false })
    }
}

@Composable
fun SavedSetsScreen(state: SettingsUi, vm: SettingsViewModel, onBack: () -> Unit) {
    var editing by rememberSaveable { mutableStateOf<Long?>(null) }
    var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
    Page(stringResource(R.string.settings_saved_sets), onBack) {
        Text(stringResource(R.string.sets_body), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
        SectionGap()
        if (state.sets.isEmpty()) EmptyState(stringResource(R.string.sets_empty))
        state.sets.forEach { g ->
            FbDivider()
            DividerRow(
                title = g.name,
                subtitle = pluralRes(R.plurals.apps_count, csv(g.packages).size),
                onClick = { editing = g.id },
                trailing = {
                    Column {
                        TextLink(stringResource(R.string.action_rename), { renaming = g.id }, accent = false)
                        TextLink(stringResource(R.string.set_delete), { vm.deleteSet(g) }, accent = false)
                    }
                },
            )
        }
        FbDivider()
    }
    state.sets.firstOrNull { it.id == editing }?.let { g ->
        AppPickerSheet(PickerContext.SET, csv(g.packages), onDismiss = { editing = null }, onDone = { vm.updateSet(g, it); editing = null })
    }
    state.sets.firstOrNull { it.id == renaming }?.let { g -> RenameDialog(g, { renaming = null }) { vm.renameSet(g, it); renaming = null } }
}

@Composable
private fun RenameDialog(g: AppGroup, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf(g.name) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = Fb.surface,
        title = { Text(stringResource(R.string.action_rename), style = FbType.heading) },
        text = { LabeledField(stringResource(R.string.picker_set_name), name, { name = it }, g.name, maxLength = 40, horizontalPadding = 0.dp) },
        confirmButton = { TextButton(onClick = { onDone(name) }, enabled = name.isNotBlank()) { Text(stringResource(R.string.action_save), color = Fb.accent) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel), color = Fb.textSecondary) } },
    )
}

@Composable
fun DefaultBlockScreen(state: SettingsUi, vm: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    Page(stringResource(R.string.settings_default_block), onBack) {
        Text(stringResource(R.string.default_block_body), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
        SectionGap()
        SectionLabel(stringResource(R.string.session_type_label))
        SegmentedControl(
            listOf(SessionType.TIMED to stringResource(R.string.session_timed), SessionType.INTERVALS to stringResource(R.string.session_intervals),
                SessionType.INDEFINITE to stringResource(R.string.session_until_stop)),
            state.defaultType, vm::setDefaultType,
        )
        SectionGap()
        Stepper(stringResource(R.string.duration_label), Fmt.minutes(context, state.defaultMinutes), { vm.setDefaultMinutes(state.defaultMinutes - 5) }, { vm.setDefaultMinutes(state.defaultMinutes + 5) })
        SectionGap()
        SectionLabel(stringResource(R.string.field_strength))
        SegmentedControl(
            listOf(Strength.NORMAL to stringResource(R.string.strength_normal), Strength.STRICT to stringResource(R.string.strength_strict)),
            state.defaultStrength, vm::setDefaultStrength, enabled = state.defaultType != SessionType.INDEFINITE,
        )
        Text(stringResource(R.string.strict_lock_consequence), style = FbType.caption, modifier = Modifier.padding(horizontal = Fb.gutter, vertical = 8.dp))
    }
}

/** One screen controlling every notification type (spec 4.9). */
@Composable
fun NotificationsScreen(state: SettingsUi, vm: SettingsViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    RefreshOnResume(vm::refresh)
    Page(stringResource(R.string.settings_notifications), onBack) {
        if (!state.systemNotifications) {
            Text(stringResource(R.string.notif_system_off), style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter))
            TextLink(stringResource(R.string.action_fix), { PermissionHealth.open(context, Requirement.NOTIFICATIONS) }, modifier = Modifier.padding(start = Fb.gutter - 8.dp))
            SectionGap()
        }
        listOf(
            Triple(Notifier.Kind.BLOCK_STATUS, R.string.notif_block_status, R.string.notif_block_status_desc),
            Triple(Notifier.Kind.RULES, R.string.notif_rules, R.string.notif_rules_desc),
            Triple(Notifier.Kind.REMINDERS, R.string.notif_usage, R.string.notif_usage_desc),
            Triple(Notifier.Kind.SUMMARY, R.string.notif_summary, R.string.notif_summary_desc),
            Triple(Notifier.Kind.PROBLEMS, R.string.notif_problems, R.string.notif_problems_desc),
        ).forEach { (kind, title, desc) ->
            FbDivider()
            val on = state.notify[kind] ?: kind.defaultOn
            DividerRow(title = stringResource(title), subtitle = stringResource(desc), trailing = { FbSwitch(on, { vm.setNotify(kind, it) }, contentDescription = stringResource(title)) })
        }
        FbDivider()
    }
}

@Composable
fun WidgetScreen(state: SettingsUi, vm: SettingsViewModel, onBack: () -> Unit) {
    Page(stringResource(R.string.settings_widget), onBack) {
        Text(stringResource(R.string.widget_body), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
        SectionGap()
        SegmentedControl(
            listOf("last" to stringResource(R.string.widget_repeat_last), "default" to stringResource(R.string.widget_default)),
            state.widgetAction, vm::setWidgetAction,
        )
    }
}

@Composable
fun DataScreen(state: SettingsUi, vm: SettingsViewModel, onBack: () -> Unit) {
    var confirm by rememberSaveable { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let(vm::export) }
    Page(stringResource(R.string.settings_data), onBack) {
        Text(stringResource(R.string.data_body), style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter))
        Spacer(Modifier.height(8.dp))
        Text(stringResource(R.string.data_stores), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
        SectionGap()
        Column(Modifier.padding(horizontal = Fb.gutter)) {
            SecondaryButton(stringResource(R.string.data_export), { export.launch("focusblock-export.json") })
            Spacer(Modifier.height(10.dp))
            SecondaryButton(stringResource(R.string.data_delete_history), { confirm = true })
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false }, containerColor = Fb.surface,
            title = { Text(stringResource(R.string.data_delete_title), style = FbType.heading) },
            text = { Text(stringResource(R.string.data_delete_body), style = FbType.body) },
            confirmButton = { TextButton(onClick = { confirm = false; vm.deleteHistory() }) { Text(stringResource(R.string.action_delete), color = Fb.warning) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.action_cancel), color = Fb.textSecondary) } },
        )
    }
    MessageDialog(state, vm)
}

@Composable
fun TroubleshootingScreen(state: SettingsUi, vm: SettingsViewModel, onBack: () -> Unit, onTested: (String) -> Unit) {
    val context = LocalContext.current
    RefreshOnResume(vm::refresh)
    Page(stringResource(R.string.settings_troubleshooting), onBack) {
        SectionLabel(stringResource(R.string.troubleshoot_samsung_title))
        listOf(R.string.troubleshoot_samsung_1, R.string.troubleshoot_samsung_2, R.string.troubleshoot_samsung_3).forEachIndexed { i, s ->
            Text("${i + 1}. " + stringResource(s), style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter, vertical = 4.dp))
        }
        SectionGap()
        SectionLabel(stringResource(R.string.troubleshoot_other_title))
        listOf(R.string.troubleshoot_other_1, R.string.troubleshoot_other_2).forEachIndexed { i, s ->
            Text("${i + 1}. " + stringResource(s), style = FbType.body, modifier = Modifier.padding(horizontal = Fb.gutter, vertical = 4.dp))
        }
        SectionGap()
        SectionLabel(stringResource(R.string.diagnostics))
        FbDivider()
        DividerRow(title = stringResource(R.string.diag_service), value = stringResource(if (state.serviceConnected) R.string.diag_service_running else R.string.diag_service_stopped))
        FbDivider()
        DividerRow(title = stringResource(R.string.diag_last_event), value = if (state.lastEventAt > 0) Fmt.time(context, state.lastEventAt) else stringResource(R.string.diag_never))
        FbDivider()
        DividerRow(title = stringResource(R.string.diag_next_check), value = state.nextCheck?.let { Fmt.time(context, it) } ?: stringResource(R.string.diag_none))
        FbDivider()
        SectionGap()
        Text(stringResource(R.string.diag_test_body), style = FbType.body.copy(color = Fb.textSecondary), modifier = Modifier.padding(horizontal = Fb.gutter))
        Spacer(Modifier.height(8.dp))
        Column(Modifier.padding(horizontal = Fb.gutter)) { PrimaryButton(stringResource(R.string.diag_test), { vm.testBlock(onTested) }) }
        SectionGap()
        SectionLabel(stringResource(R.string.diag_recent))
        state.events.take(20).forEach { e ->
            FbDivider()
            DividerRow(title = e.kind, subtitle = e.detail.ifBlank { null }, value = Fmt.time(context, e.time))
        }
        FbDivider()
    }
    MessageDialog(state, vm)
}
