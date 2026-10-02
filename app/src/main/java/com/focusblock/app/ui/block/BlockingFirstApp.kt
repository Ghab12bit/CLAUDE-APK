package com.focusblock.app.ui.block

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.blocking.*
import com.focusblock.app.database.entity.*
import com.focusblock.app.ui.focus.InstalledAppIcon
import com.focusblock.app.ui.focus.formatRemaining
import com.focusblock.app.ui.focus.formatScheduleTime
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun BlockingFirstTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val view = androidx.compose.ui.platform.LocalView.current
    SideEffect {
        (view.context as? android.app.Activity)?.window?.let { window ->
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !dark
            androidx.core.view.WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = !dark
            window.statusBarColor = android.graphics.Color.TRANSPARENT
            window.navigationBarColor = android.graphics.Color.TRANSPARENT
        }
    }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFE4AA8A), onPrimary = Color(0xFF24211E),
        primaryContainer = Color(0xFF463125), background = Color(0xFF1C1B19), surface = Color(0xFF292724),
        onBackground = Color(0xFFF3F0E9), onSurface = Color(0xFFF3F0E9), onSurfaceVariant = Color(0xFFBCB6AC),
        outline = Color(0xFF49443E), surfaceVariant = Color(0xFF35312B))
    else lightColorScheme(primary = Color(0xFF95472F), onPrimary = Color(0xFFFFFEFA),
        primaryContainer = Color(0xFFF0E0D5), background = Color(0xFFF6F4EF), surface = Color.White,
        onBackground = Color(0xFF272522), onSurface = Color(0xFF272522), onSurfaceVariant = Color(0xFF68635C),
        outline = Color(0xFFD8D3C9), surfaceVariant = Color(0xFFEAE6DE))
    MaterialTheme(colorScheme = colors, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingFirstApp(onAdvanced: () -> Unit, vm: BlockViewModel = hiltViewModel()) = BlockingFirstTheme {
    val s by vm.state.collectAsState()
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(0) }
    var sheet by rememberSaveable { mutableStateOf<String?>(null) }
    var setup by remember { mutableStateOf(BlockSetup()) }
    var initialized by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<List<String>>(emptyList()) }
    var pickerTarget by remember { mutableStateOf("session") }
    var rule by remember { mutableStateOf<Schedule?>(null) }
    var importedRule by remember { mutableStateOf<ImportedRuleStore.Rule?>(null) }
    var limit by remember { mutableStateOf<AppTimeLimit?>(null) }
    var previous by remember { mutableStateOf<QuickBlockSession?>(null) }
    var completed by remember { mutableStateOf<QuickBlockSession?>(null) }
    LaunchedEffect(s.loading, s.last) { if (!s.loading && !initialized) { setup = s.last.copy(packages = s.savedSelection); initialized = true } }
    LaunchedEffect(s.session) {
        if (previous != null && s.session == null) { completed = s.sessions.firstOrNull { it.id == previous?.id } ?: previous; sheet = "complete" }
        previous = s.session
    }
    fun picker(target: String, values: List<String>) { pickerTarget = target; selected = values; sheet = "picker" }
    fun newRule(name: String, start: Int, end: Int) {
        rule = Schedule(name = name, startTimeMinutes = start, endTimeMinutes = end,
            daysOfWeek = "1,2,3,4,5,6,7", blockedPackages = setup.packages.joinToString(","), iconType = if (name == "Bedtime") ScheduleIconType.SLEEP else ScheduleIconType.CUSTOM)
        sheet = "rule"
    }
    Scaffold(containerColor = MaterialTheme.colorScheme.background,
        topBar = { Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 22.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.size(21.dp, 20.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.Bottom) {
                Box(Modifier.size(6.dp, 20.dp).background(MaterialTheme.colorScheme.onBackground, RoundedCornerShape(2.dp)))
                Box(Modifier.size(6.dp, 13.dp).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)))
            }
            Text("FocusBlock", fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f).padding(start = 8.dp))
            IconButton(onClick = { sheet = "settings" }) { Icon(Icons.Outlined.Tune, "Settings and permissions") }
        } },
        bottomBar = { Column { Line(); Row(Modifier.fillMaxWidth().navigationBarsPadding()) {
            listOf("Block", "Rules", "Activity").forEachIndexed { i, label ->
                Column(Modifier.weight(1f).clickable { tab = i }.padding(horizontal = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == i) MaterialTheme.colorScheme.primary else Color.Transparent))
                    Icon(listOf(Icons.Outlined.Pause, Icons.Outlined.AltRoute, Icons.Outlined.BarChart)[i], null, modifier = Modifier.padding(top = 12.dp).size(22.dp))
                    Text(label, fontSize = 12.sp, modifier = Modifier.padding(top = 5.dp, bottom = 14.dp), color = if (tab == i) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        } } }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp)) {
            if (s.loading) { CircularProgressIndicator(Modifier.padding(24.dp)); Muted("Loading saved protection…") }
            else when (tab) {
                0 -> {
                    if (!s.ready) {
                        Heading("Protection needs attention."); Gap(); Muted("Blocking may be interrupted. Your apps and rules are saved.")
                        Gap(); Action("Restore access") { sheet = "permissions" }
                    }
                    val session = s.session
                    if (session == null) {
                        if (s.ready) { Heading("Block distractions."); Muted("Permissions ready · choose when to block") }
                        if (s.last.packages.isNotEmpty()) TextButton(onClick = { vm.start(s.last) }, enabled = s.ready && !s.busy, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Outlined.Replay, null); Column(Modifier.weight(1f).padding(12.dp)) {
                                Text("Repeat last block", color = MaterialTheme.colorScheme.onBackground)
                                Muted("${s.last.mode} · ${s.last.minutes} min · ${s.last.packages.size} apps · ${if (s.last.strict) "locked" else "lock off"}")
                            }; Icon(Icons.Outlined.PlayArrow, null)
                        }
                        Line(); Section("Apps to block (${setup.packages.size})", "Edit selection") { picker("session", setup.packages) }
                        AppStrip(s, setup.packages) { picker("session", setup.packages) }
                        Gap(); ModeRow(listOf("Timed", "Cycles", "Until stopped"), listOf("timed", "cycles", "manual").indexOf(setup.mode)) { i ->
                            setup = setup.copy(mode = listOf("timed", "cycles", "manual")[i], strict = if (i == 2) false else setup.strict)
                        }
                        Row(Modifier.padding(top = 21.dp, bottom = 13.dp), verticalAlignment = Alignment.Bottom) {
                            Text(if (setup.mode == "manual") "On" else setup.minutes.toString(), fontSize = 48.sp, letterSpacing = (-2).sp)
                            Muted(if (setup.mode == "manual") "  until you stop" else "  minutes")
                        }
                        when (setup.mode) {
                            "timed" -> ChoiceRow(listOf("25 min", "45 min", "60 min", "Custom"), listOf(25, 45, 60).indexOf(setup.minutes)) {
                                if (it == 3) sheet = "duration" else setup = setup.copy(minutes = listOf(25, 45, 60)[it])
                            }
                            "cycles" -> TextButton(onClick = { sheet = "cycles" }) { Text("${setup.rest} min break · ${setup.rounds} rounds   Edit") }
                            else -> Muted("No countdown. Stop whenever you choose. Strict Lock needs a timed end.")
                        }
                        Gap(); Line()
                        Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.Lock, null); Column(Modifier.weight(1f).padding(horizontal = 10.dp)) { Text("Strict Lock"); Muted("Cannot shorten or stop this block") }
                            Switch(setup.strict, { if (it) sheet = "strict" else setup = setup.copy(strict = false) }, enabled = setup.mode != "manual")
                        }
                        Action(if (setup.mode == "manual") "Start blocking" else if (setup.mode == "cycles") "Start focus cycles" else "Start ${setup.minutes}-minute block", s.ready && setup.packages.isNotEmpty() && !s.busy) { vm.start(setup) }
                        Gap(12); Muted("Calls and essential apps stay available")
                        Gap(); Line(); TextButton(onClick = { tab = 1 }) { Text("${s.schedules.count { it.isEnabled }} scheduled rules · View rules") }
                    } else {
                        val m = s.metadata.takeIf { it.id == session.id } ?: BlockSessionMetadata()
                        val phase = BlockSessionPolicy.phase(session.startTime, session.endTime, s.now, m.focus, m.rest, m.rounds)
                        if (s.ready) Heading(if (phase.blocking) "Distractions paused." else "Take a break.")
                        Column(Modifier.fillMaxWidth().padding(top = 24.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(14.dp)).padding(22.dp)) {
                            Text(if (!s.ready) "Protection interrupted" else if (!phase.blocking) "Scheduled break" else if (m.strict) "Strict Lock active" else "Blocking active", color = MaterialTheme.colorScheme.primary)
                            Text(if (session.endTime == null) "Until stopped" else formatRemaining(phase.remaining), fontSize = 48.sp, modifier = Modifier.padding(vertical = 16.dp))
                            Muted(if (m.rounds > 1) "Round ${phase.round} of ${m.rounds}" else "remaining in this block")
                            session.endTime?.let { end -> LinearProgressIndicator(progress = ((s.now - session.startTime).toFloat() / (end - session.startTime).coerceAtLeast(1)).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 21.dp)) }
                        }
                        if (!phase.blocking) { Gap(); Muted("Session apps open during this break. Other schedules and limits still apply.") }
                        Section("Session apps"); AppStrip(s, QuickBlockPolicy.packages(session.blockedPackages).toList()) {}
                        if (m.exceptionUntil > s.now && m.exceptionPackage.isNotBlank()) {
                            Gap(12); Muted("${s.apps.find { it.packageName == m.exceptionPackage }?.appName ?: "One session app"} is temporarily allowed for ${formatRemaining(m.exceptionUntil - s.now)}. This allowance cannot be extended.")
                        }
                        Gap(); Detail("Blocked openings", s.logs.count { it.timestamp >= session.startTime && it.blockedBy == BlockedByType.QUICK_BLOCK }.toString())
                        Detail("Temporary access", "${(m.budget - m.used).coerceAtLeast(0)} left this session")
                        TextButton(onClick = { sheet = "essentials" }) { Text("Essential apps · always available") }
                        if (session.endTime != null) OutlinedButton(onClick = vm::extend, modifier = Modifier.fillMaxWidth(), enabled = !s.busy) { Text("Add 15 minutes") }
                        Gap(); Action("Put your phone aside") { AppUtils.goToHome(context) }
                        if (m.strict) { Gap(12); Muted("This block cannot be ended early") }
                        else TextButton(onClick = { sheet = "end" }, modifier = Modifier.fillMaxWidth()) { Text("End block") }
                    }
                }
                1 -> {
                    Heading("Automatic blocking."); Muted("Set your boundaries once. Keep control of them here.")
                    Gap(); Detail("Rules enabled", (s.schedules.count { it.isEnabled } + s.limits.count { it.isEnabled }).toString())
                    Section("BY TIME")
                    if (s.schedules.isEmpty()) Muted("No scheduled rules yet. Add a work, bedtime or morning window.")
                    s.schedules.forEach { r -> Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f).clickable { rule = r; sheet = "rule" }) {
                            Text(r.name); Muted("${formatScheduleTime(r.startTimeMinutes)} – ${formatScheduleTime(r.endTimeMinutes)}")
                            Muted("${r.daysOfWeek.split(',').size} days/week · ${if (r.isStrictMode) "Locked while active" else "Editable"}")
                            Gap(8); Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                                QuickBlockPolicy.packages(r.blockedPackages).take(4).forEach { p ->
                                    val app = s.apps.find { it.packageName == p }; InstalledAppIcon(app?.icon, app?.appName ?: p, 25.dp)
                                }
                            }
                        }; Switch(r.isEnabled, { vm.toggleRule(r, it) }, enabled = !s.busy)
                    }; Line() }
                    Section("BY USAGE")
                    if (s.limits.isEmpty()) Muted("No app limits. Set a daily allowance for any chosen app.")
                    s.limits.forEach { l -> Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        InstalledAppIcon(s.apps.find { it.packageName == l.packageName }?.icon, l.appName, 36.dp)
                        Column(Modifier.weight(1f).padding(horizontal = 12.dp).clickable { limit = l; sheet = "limit" }) {
                            Text(l.appName); Muted("${l.dailyLimitMinutes} min/day · ${s.usage?.get(l.packageName)?.let { ((l.dailyLimitMinutes * 60000L - it).coerceAtLeast(0) / 60000).toString() + " min left" } ?: "Usage unavailable"}")
                        }; Switch(l.isEnabled, { vm.saveLimit(l.copy(isEnabled = it)) }, enabled = !s.busy)
                    }; Line() }
                    Gap(); Action("Add a rule") { sheet = "add" }
                    if (s.importedRules.isNotEmpty()) {
                        Section("PRESERVED RULES FROM YOUR PREVIOUS BUILD")
                        Muted("These retain their original combined conditions and app selections.")
                        s.importedRules.forEach { imported ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f).clickable { importedRule = imported; sheet = "imported" }) { Text(imported.name); Muted(imported.description()); Muted("${QuickBlockPolicy.packages(imported.packages).size} apps · ${imported.commitment.lowercase()}") }
                                Switch(imported.enabled, { vm.toggleImported(imported) }, enabled = !s.configurationLocked && !s.busy)
                            }; Line()
                        }
                    }
                    Gap(12); Muted("A session ending does not turn off another active rule.")
                    TextButton(onClick = onAdvanced) { Text("Existing advanced protection controls") }
                }
                2 -> ActivityContent(s, setup.packages, onLimit = { p ->
                    limit = s.limits.find { it.packageName == p } ?: AppTimeLimit(p, s.apps.find { it.packageName == p }?.appName ?: p, 30)
                    sheet = "limit"
                }, onPermissions = { sheet = "permissions" })
            }
            s.error?.let { Gap(); Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::clearError) { Text("Dismiss") } }
        }
    }
    if (sheet != null) ModalBottomSheet(onDismissRequest = { sheet = null }, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().heightIn(max = 660.dp).verticalScroll(rememberScrollState()).padding(horizontal = 22.dp).padding(bottom = 28.dp).imePadding()) {
            when (sheet) {
                "imported" -> importedRule?.let { r -> ImportedRuleEditor(r, { importedRule = it }, { picker("imported", QuickBlockPolicy.packages(r.packages).toList()) }, { vm.saveImported(r); sheet = null }) }
                "picker" -> {
                    Heading("Choose apps")
                    var query by remember { mutableStateOf("") }
                    OutlinedTextField(query, { query = it }, label = { Text("Search apps") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    val apps = s.apps.filter { it.appName.contains(query, true) }
                    LazyVerticalGrid(GridCells.Adaptive(76.dp), Modifier.fillMaxWidth().height(320.dp).padding(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        items(apps, key = { it.packageName }) { a ->
                            val essential = a.packageName in s.essential
                            val canSelect = !essential || pickerTarget == "essentials"
                            Column(Modifier.clickable(enabled = canSelect) {
                                selected = if (pickerTarget == "limit") listOf(a.packageName) else if (a.packageName in selected) selected - a.packageName else selected + a.packageName
                            }.padding(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                                Box { InstalledAppIcon(a.icon, a.appName, 46.dp); if (a.packageName in selected) Icon(Icons.Outlined.CheckCircle, "Selected", Modifier.align(Alignment.BottomEnd).size(22.dp).background(MaterialTheme.colorScheme.surface), tint = MaterialTheme.colorScheme.primary) }
                                Text(a.appName, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(if (essential) "Essential" else s.usage?.get(a.packageName)?.let(::usageText) ?: "—", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                    TextButton(onClick = { selected = s.last.packages }) { Text("Last selection") }
                    Action("Use ${selected.size} apps") {
                        when (pickerTarget) {
                            "imported" -> { importedRule = importedRule?.copy(packages = selected.joinToString(",")); sheet = "imported" }
                            "rule" -> { rule = rule?.copy(blockedPackages = selected.joinToString(",")); sheet = "rule" }
                            "limit" -> { selected.firstOrNull()?.let { p -> limit = s.limits.find { it.packageName == p } ?: AppTimeLimit(p, s.apps.find { it.packageName == p }?.appName ?: p, 30) }; sheet = "limit" }
                            "essentials" -> { vm.essentials(selected.toSet()); sheet = "essentials" }
                            else -> { setup = setup.copy(packages = selected.filter { it !in s.essential }); vm.saveSelection(setup.packages); sheet = null }
                        }
                    }
                }
                "strict" -> {
                    Heading("Turn on Strict Lock?"); Muted("Until the timer ends, you cannot stop this block, shorten it, or change its apps. Android permissions can still be revoked outside FocusBlock.")
                    Gap(); Text("Temporary access during this block")
                    ChoiceRow(listOf("Once, up to 2 min", "None"), if (setup.budget == 1) 0 else 1) { setup = setup.copy(budget = if (it == 0) 1 else 0) }
                    Gap(); Muted("Decide before starting. This allowance cannot be replenished. Calls and essentials do not use it.")
                    Gap(); Action("Enable Strict Lock") { setup = setup.copy(strict = true); sheet = null }
                }
                "duration", "cycles" -> {
                    Heading(if (sheet == "cycles") "Focus cycles" else "Set a duration")
                    var minutes by remember { mutableStateOf(setup.minutes.toString()) }
                    var rest by remember { mutableStateOf(setup.rest.toString()) }
                    var rounds by remember { mutableStateOf(setup.rounds.toString()) }
                    NumberField("Block minutes (1–720)", minutes) { minutes = it }
                    if (sheet == "cycles") { NumberField("Break minutes (1–30)", rest) { rest = it }; NumberField("Rounds (2–8)", rounds) { rounds = it }; Muted("Selected apps open during breaks. Other rules still apply.") }
                    Gap(); Action("Use duration", minutes.toIntOrNull() in 1..720 && rest.toIntOrNull() in 1..30 && rounds.toIntOrNull() in 2..8) {
                        setup = setup.copy(minutes = minutes.toInt(), rest = rest.toInt(), rounds = rounds.toInt()); sheet = null
                    }
                }
                "end" -> { Heading("End this block?"); Muted("Other active schedules and limits will continue."); Gap(); Action("End block") { vm.stop(); sheet = null } }
                "complete" -> {
                    Heading("Block ended")
                    completed?.let { c -> Detail("Block duration", usageText(((c.endTime ?: s.now) - c.startTime).coerceAtLeast(0))); Detail("Blocked openings", s.logs.count { it.timestamp in c.startTime..(c.endTime ?: s.now) && it.blockedBy == BlockedByType.QUICK_BLOCK }.toString()) }
                    Gap(); Muted("Block time is not a measure of concentration. Your rules are unchanged.")
                    Gap(); Action("Block for another 15 minutes", s.ready) { vm.start(s.last.copy(mode = "timed", minutes = 15)); sheet = null }
                    TextButton(onClick = { sheet = null }, modifier = Modifier.fillMaxWidth()) { Text("Done") }
                }
                "add" -> { Heading("Choose a rule")
                    SheetRow("Schedule", "Block apps at set times") { newRule("Scheduled block", 9 * 60, 17 * 60) }
                    SheetRow("Daily limit", "One app, one daily allowance") { limit = null; picker("limit", emptyList()) }
                    SheetRow("Bedtime", "An overnight window") { newRule("Bedtime", 22 * 60 + 30, 7 * 60) }
                    SheetRow("Morning pause", "A fixed morning window you choose") { newRule("Morning pause", 7 * 60, 8 * 60 + 30) }
                }
                "rule" -> rule?.let { r -> RuleEditor(r, { rule = it }, { picker("rule", QuickBlockPolicy.packages(r.blockedPackages).toList()) }, { vm.saveRule(r); sheet = null }) }
                "limit" -> limit?.let { l ->
                    Heading("${l.appName} limit")
                    var value by remember(l.packageName) { mutableStateOf(l.dailyLimitMinutes.toString()) }
                    NumberField("Daily minutes (1–720)", value) { value = it }
                    Muted("Counts usage since local midnight, including use before you created this limit.")
                    Gap(); Action("Save limit", value.toIntOrNull() in 1..720 && l.packageName !in s.essential) { vm.saveLimit(l.copy(dailyLimitMinutes = value.toInt())); sheet = null }
                }
                "settings" -> {
                    Heading("Settings")
                    SheetRow("Permissions", if (s.ready) "Ready" else "Action needed") { sheet = "permissions" }
                    SheetRow("Essential apps", "Always available") { sheet = "essentials" }
                    Section("Offline reminder")
                    listOf("" to "Off", "away" to "Put the phone out of reach", "stretch" to "Stand and stretch", "read" to "Read a page").forEach { (value, label) ->
                        Row(Modifier.fillMaxWidth().clickable { vm.reminder(value) }, verticalAlignment = Alignment.CenterVertically) { RadioButton(s.reminder == value, { vm.reminder(value) }); Text(label) }
                    }
                    Muted("No task, proof, points or completion tracking.")
                    SheetRow("Advanced controls", "Existing Hard Mode, app timer, usage cycles and notifications") { sheet = null; onAdvanced() }
                }
                "permissions" -> {
                    Heading("Restore protection")
                    SheetRow("Accessibility", if (PermissionUtils.hasAccessibilityServiceEnabled(context)) "Enabled" else "Required for reliable blocking") { PermissionUtils.openAccessibilitySettings(context) }
                    SheetRow("Usage access", if (PermissionUtils.hasUsageStatsPermission(context)) "Enabled" else "Required for usage and limits") { PermissionUtils.openUsageAccessSettings(context) }
                    SheetRow("Display over apps", if (PermissionUtils.hasOverlayPermission(context)) "Enabled" else "Required for intervention") { PermissionUtils.openOverlaySettings(context) }
                    SheetRow("Notifications", if (PermissionUtils.hasNotificationPermission(context)) "Enabled" else "Check system settings") { PermissionUtils.openNotificationSettings(context) }
                    SheetRow("Battery settings", "On Samsung, also remove FocusBlock from sleeping apps") { PermissionUtils.openBatteryOptimizationSettings(context) }
                    Muted("Permissions alone cannot guarantee uninterrupted protection. Force-stop and device power settings can interrupt services.")
                }
                "essentials" -> {
                    Heading("Always available"); Muted("Phone, emergency calling and system navigation are protected. Essentials apply across rules and sessions.")
                    Gap(); AppStrip(s, s.essential.filter { p -> s.apps.any { it.packageName == p } }) {}
                    Gap(); Action("Edit essential apps", !s.metadata.strict || s.session == null) { picker("essentials", s.essential.toList()) }
                }
            }
        }
    }
}

@Composable
fun BlockingFirstOnboarding(onComplete: () -> Unit) = BlockingFirstTheme {
    val context = LocalContext.current
    var refresh by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(1000); refresh++ } }
    val access = remember(refresh) { PermissionUtils.hasAccessibilityServiceEnabled(context) }
    val usage = remember(refresh) { PermissionUtils.hasUsageStatsPermission(context) }
    val overlay = remember(refresh) { PermissionUtils.hasOverlayPermission(context) }
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(24.dp)) {
            Text("FocusBlock", color = MaterialTheme.colorScheme.primary); Gap(30)
            Heading("Make room for your day.")
            Muted("Choose the apps you want a break from. Set a timer or a recurring rule. Calls and essential apps stay available.")
            Gap(28); Line(); Section("Enable blocking")
            SheetRow("Accessibility", if (access) "Enabled" else "Detect an app opening and show your block screen") { PermissionUtils.openAccessibilitySettings(context) }
            SheetRow("Usage access", if (usage) "Enabled" else "Measure app time for limits and Activity") { PermissionUtils.openUsageAccessSettings(context) }
            SheetRow("Display over apps", if (overlay) "Enabled" else "Show the intervention when a chosen app is blocked") { PermissionUtils.openOverlaySettings(context) }
            Gap(); Muted("Strict Lock is optional and explained before you start. You decide which apps and how long.")
            Gap(28); Action(if (access && usage && overlay) "Choose my apps" else "Continue setup", onClick = onComplete)
            Gap(); Muted("Blocking stays unavailable until the required permissions are enabled. You can inspect the app first.")
        }
    }
}

@Composable internal fun Heading(text: String) { Text(text, fontSize = 30.sp, lineHeight = 34.sp, letterSpacing = (-1).sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(top = 8.dp, bottom = 10.dp)) }
@Composable internal fun Muted(text: String) { Text(text, fontSize = 12.sp, lineHeight = 18.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
@Composable internal fun Gap(size: Int = 20) { Spacer(Modifier.height(size.dp)) }
@Composable internal fun Line() { Divider(color = MaterialTheme.colorScheme.outline) }
@Composable internal fun Action(label: String, enabled: Boolean = true, onClick: () -> Unit) { Button(onClick, Modifier.fillMaxWidth().heightIn(min = 53.dp), enabled, shape = RoundedCornerShape(10.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onBackground, contentColor = MaterialTheme.colorScheme.background)) { Text(label) } }
@Composable internal fun Section(title: String, action: String? = null, onClick: () -> Unit = {}) { Row(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) { Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium); if (action != null) TextButton(onClick) { Text(action) } } }
@Composable internal fun Detail(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceBetween) { Muted(label); Text(value, fontSize = 14.sp) }; Line() }
@Composable internal fun SheetRow(title: String, subtitle: String, action: () -> Unit) { Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 17.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(title); Muted(subtitle) }; Icon(Icons.Outlined.ChevronRight, null) }; Line() }
@Composable internal fun ChoiceRow(labels: List<String>, selected: Int, choose: (Int) -> Unit) { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) { labels.forEachIndexed { i, label -> OutlinedButton(onClick = { choose(i) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp), contentPadding = PaddingValues(horizontal = 3.dp), shape = RoundedCornerShape(7.dp), colors = ButtonDefaults.outlinedButtonColors(containerColor = if (i == selected) MaterialTheme.colorScheme.onBackground else Color.Transparent, contentColor = if (i == selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onBackground)) { Text(label, fontSize = 12.sp) } } } }
@Composable private fun ModeRow(labels: List<String>, selected: Int, choose: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, label ->
            Column(Modifier.weight(1f).clickable { choose(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) { Text(label, fontSize = 13.sp, color = if(i == selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(Modifier.fillMaxWidth().height(2.dp).background(if(i == selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline))
            }
        }
    }
}
@Composable internal fun NumberField(label: String, value: String, change: (String) -> Unit) { OutlinedTextField(value, change, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp)) }
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AppStrip(s: BlockUiState, packages: List<String>, click: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        packages.forEach { p -> val app = s.apps.find { it.packageName == p }; Column(Modifier.width(58.dp).clickable(onClick = click), horizontalAlignment = Alignment.CenterHorizontally) { InstalledAppIcon(app?.icon, app?.appName ?: p, 46.dp); Text(app?.appName ?: "Unavailable", fontSize = 10.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) } }
        if (packages.isEmpty()) TextButton(onClick = click) { Icon(Icons.Outlined.Add, null); Text("Choose apps") }
    }
}
internal fun usageText(ms: Long): String { val minutes = ms / 60000; return if (minutes >= 60) "${minutes / 60}h ${minutes % 60}m" else "$minutes min" }

@Composable private fun RuleEditor(r: Schedule, change: (Schedule) -> Unit, apps: () -> Unit, save: () -> Unit) {
    Heading("Edit protection")
    OutlinedTextField(r.name, { change(r.copy(name = it)) }, label = { Text("Rule name") }, modifier = Modifier.fillMaxWidth())
    var start by remember(r.id) { mutableStateOf("%02d:%02d".format(r.startTimeMinutes / 60, r.startTimeMinutes % 60)) }
    var end by remember(r.id) { mutableStateOf("%02d:%02d".format(r.endTimeMinutes / 60, r.endTimeMinutes % 60)) }
    fun minute(s: String): Int? { val p = s.split(':'); if (p.size != 2) return null; val h = p[0].toIntOrNull() ?: return null; val m = p[1].toIntOrNull() ?: return null; return if (h in 0..23 && m in 0..59) h * 60 + m else null }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(start, { start = it; minute(it)?.let { t -> change(r.copy(startTimeMinutes = t)) } }, label = { Text("Start HH:mm") }, modifier = Modifier.weight(1f))
        OutlinedTextField(end, { end = it; minute(it)?.let { t -> change(r.copy(endTimeMinutes = t)) } }, label = { Text("End HH:mm") }, modifier = Modifier.weight(1f))
    }
    Gap(); val days = r.daysOfWeek.split(',').mapNotNull(String::toIntOrNull).toSet()
    Row(Modifier.fillMaxWidth()) { listOf("M", "T", "W", "T", "F", "S", "S").forEachIndexed { i, d -> TextButton(onClick = { change(r.copy(daysOfWeek = (if (i + 1 in days) days - (i + 1) else days + (i + 1)).sorted().joinToString(","))) }, modifier = Modifier.weight(1f), contentPadding = PaddingValues(0.dp)) { Text(d, color = if (i + 1 in days) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant) } } }
    SheetRow("Apps to block", "${QuickBlockPolicy.packages(r.blockedPackages).size} selected", apps)
    Row(verticalAlignment = Alignment.CenterVertically) { Text("Lock while active", Modifier.weight(1f)); Switch(r.isStrictMode, { change(r.copy(isStrictMode = it)) }) }
    Muted("No weakening this rule during its active window. Overnight windows continue into the next morning.")
    Gap(); Action("Save rule", r.name.isNotBlank() && r.blockedPackages.isNotBlank() && days.isNotEmpty() && minute(start) != null && minute(end) != null && minute(start) != minute(end), save)
}

@Composable private fun ActivityContent(s: BlockUiState, chosen: List<String>, onLimit: (String) -> Unit, onPermissions: () -> Unit) {
    var all by rememberSaveable { mutableStateOf(false) }
    Heading("Your usage, clearly."); Muted("Today")
    Gap(); ModeRow(listOf("Chosen apps", "All apps"), if (all) 1 else 0) { all = it == 1 }
    if (s.usage == null) { Gap(); Muted("Usage data is unavailable, not zero."); Action("Review permissions", onClick = onPermissions); return }
    val usage = s.usage.filterKeys { all || it in chosen }
    val prior = s.yesterday?.filterKeys { all || it in chosen }
    Gap(); Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) { Muted(if(all) "All recorded apps" else "${chosen.size} apps you chose"); Text(usageText(usage.values.sum()), fontSize = 40.sp) }
        Column { Muted("Yesterday at this time"); Text(prior?.values?.sum()?.let(::usageText) ?: "Unavailable", fontSize = 14.sp) }
    }
    Gap(); Line(); Gap(12); Muted("Same app selection and time window. Necessary use is not labelled wasted time.")
    val midnight = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
    val completed = s.sessions.filter { !it.isActive && it.endTime != null && it.endTime <= s.now && it.endTime > midnight }
    Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(usageText(completed.sumOf { ((it.endTime ?: it.startTime) - maxOf(it.startTime, midnight)).coerceAtLeast(0) })); Muted("Completed block time") }
        Box(Modifier.width(1.dp).height(40.dp).background(MaterialTheme.colorScheme.outline))
        Column(Modifier.weight(1f).padding(start = 20.dp)) { Text("${s.logs.count { it.timestamp >= midnight }} attempts"); Muted("Blocked app openings") }
    }; Line()
    Section("Most used apps", "Tap to set a limit")
    if (usage.isEmpty()) Muted("No recorded usage in this selection.")
    usage.entries.sortedByDescending { it.value }.take(10).forEach { (pkg, time) ->
        val app = s.apps.find { it.packageName == pkg }
        Row(Modifier.fillMaxWidth().clickable(enabled = pkg !in s.essential) { onLimit(pkg) }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            InstalledAppIcon(app?.icon, app?.appName ?: pkg, 36.dp)
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(app?.appName ?: pkg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(progress = (time.toFloat() / (usage.values.maxOrNull() ?: 1).coerceAtLeast(1)).coerceIn(0f, 1f), modifier = Modifier.fillMaxWidth().padding(top = 7.dp).height(4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, trackColor = MaterialTheme.colorScheme.surfaceVariant)
                if (pkg in s.essential) Muted("Always available")
            }; Text(usageText(time))
        }; Line()
    }
    val candidate = usage.entries.filter { it.key in chosen && it.key !in s.essential && s.limits.none { l -> l.packageName == it.key } }.maxByOrNull { it.value }
    if (candidate != null && candidate.value >= 30 * 60000L) {
        Gap(); Text("${s.apps.find { it.packageName == candidate.key }?.appName ?: "A chosen app"}: ${usageText(candidate.value)} today.")
        Muted("If you want a daily boundary, choose an allowance. Nothing changes automatically.")
        TextButton(onClick = { onLimit(candidate.key) }) { Text("Review a daily limit") }
    }
    Section("Block history")
    if (s.logs.isEmpty()) Muted("Blocked openings will appear here.")
    s.logs.take(12).forEach { log -> Detail("${SimpleDateFormat("dd MMM · HH:mm", Locale.getDefault()).format(Date(log.timestamp))} · ${log.appName}", log.blockedBy.name.lowercase().replace('_', ' ')) }
    Gap(); Muted("Block time is not a measure of concentration. We do not infer why you opened an app.")
}
