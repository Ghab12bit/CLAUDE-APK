package com.focusblock.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.focusblock.app.R
import com.focusblock.app.core.AppGraph
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.ui.activity.ActivityScreen
import com.focusblock.app.ui.activity.ActivityViewModel
import com.focusblock.app.ui.block.BlockScreen
import com.focusblock.app.ui.block.BlockViewModel
import com.focusblock.app.ui.onboarding.OnboardingFlow
import com.focusblock.app.ui.rules.EditorRequest
import com.focusblock.app.ui.rules.RuleEditorScreen
import com.focusblock.app.ui.rules.RuleEditorViewModel
import com.focusblock.app.ui.rules.RulesScreen
import com.focusblock.app.ui.rules.RulesViewModel
import com.focusblock.app.ui.settings.DataScreen
import com.focusblock.app.ui.settings.DefaultBlockScreen
import com.focusblock.app.ui.settings.EssentialsScreen
import com.focusblock.app.ui.settings.HealthScreen
import com.focusblock.app.ui.settings.NotificationsScreen
import com.focusblock.app.ui.settings.SavedSetsScreen
import com.focusblock.app.ui.settings.SettingsHome
import com.focusblock.app.ui.settings.SettingsRoutes
import com.focusblock.app.ui.settings.SettingsViewModel
import com.focusblock.app.ui.settings.TroubleshootingScreen
import com.focusblock.app.ui.settings.WidgetScreen
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbTheme
import com.focusblock.app.ui.theme.FbType
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val openRequest = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        openRequest.value = intent?.getStringExtra(EXTRA_OPEN)
        setContent {
            FbTheme {
                Box(Modifier.fillMaxSize().background(Fb.bg)) { Root(openRequest.value) { openRequest.value = null } }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        openRequest.value = intent.getStringExtra(EXTRA_OPEN)
    }

    override fun onResume() {
        super.onResume()
        val graph = AppGraph.get(applicationContext)
        // Ends finished blocks, refreshes notifications/widget and re-checks health on every return.
        lifecycleScope.launch { graph.tick("app_resume") }
    }

    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_END_SHEET = "end_sheet"
        const val OPEN_HEALTH = "health"
        const val OPEN_ACTIVITY = "activity"
    }
}

private object Tabs {
    const val BLOCK = "block"
    const val RULES = "rules"
    const val ACTIVITY = "activity"
    val all = listOf(BLOCK, RULES, ACTIVITY)
}

private const val EDITOR = "editor/{kind}?id={id}&template={template}&start={start}&end={end}&days={days}&apps={apps}&name={name}"

private fun editorRoute(r: EditorRequest): String =
    "editor/${r.kind.name}?id=${r.id}&template=${android.net.Uri.encode(r.template)}&start=${r.start}&end=${r.end}" +
        "&days=${android.net.Uri.encode(r.days)}&apps=${android.net.Uri.encode(r.apps)}&name=${android.net.Uri.encode(r.name)}"

@Composable
private fun Root(open: String?, onOpened: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val graph = remember { AppGraph.get(context.applicationContext) }
    var onboarded by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(Unit) {
        val done = graph.db.settingsDao().getValue(PrefKeys.ONBOARDING_DONE) == "true" ||
            runCatching { graph.db.focusBlockDao().hasCompletedOnboarding() == true }.getOrDefault(false)
        onboarded = done
    }
    when (onboarded) {
        null -> Unit // A blank frame for a few milliseconds while one setting is read.
        false -> Box(Modifier.fillMaxSize().imePadding()) { OnboardingFlow { onboarded = true } }
        true -> MainNav(open, onOpened)
    }
}

@Composable
private fun MainNav(open: String?, onOpened: () -> Unit) {
    val activity = androidx.compose.ui.platform.LocalContext.current as ComponentActivity
    val graph = remember { AppGraph.get(activity.applicationContext) }
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val blockVm: BlockViewModel = viewModel(activity, factory = viewModelFactory { initializer { BlockViewModel(graph, createSavedStateHandle()) } })
    val settingsVm: SettingsViewModel = viewModel(activity, factory = viewModelFactory { initializer { SettingsViewModel(graph) } })

    LaunchedEffect(open) {
        when (open) {
            MainActivity.OPEN_HEALTH -> nav.navigate(SettingsRoutes.HEALTH)
            MainActivity.OPEN_ACTIVITY -> selectTab(nav, Tabs.ACTIVITY)
            MainActivity.OPEN_END_SHEET -> selectTab(nav, Tabs.BLOCK)
        }
        if (open != null) onOpened()
    }

    Scaffold(
        containerColor = Fb.bg,
        bottomBar = { if (route in Tabs.all) BottomBar(route) { selectTab(nav, it) } },
    ) { padding ->
        NavHost(nav, startDestination = Tabs.BLOCK, modifier = Modifier.padding(padding).imePadding()) {
            composable(Tabs.BLOCK) {
                val state by blockVm.state.collectAsStateWithLifecycle()
                BlockScreen(state, blockVm, onSettings = { nav.navigate(SettingsRoutes.HOME) },
                    onEssentials = { nav.navigate(SettingsRoutes.ESSENTIALS) }, onHealth = { nav.navigate(SettingsRoutes.HEALTH) })
            }
            composable(Tabs.RULES) {
                val vm: RulesViewModel = viewModel(factory = viewModelFactory { initializer { RulesViewModel(graph) } })
                val state by vm.state.collectAsStateWithLifecycle()
                RulesScreen(state, vm, onSettings = { nav.navigate(SettingsRoutes.HOME) }, onEdit = { nav.navigate(editorRoute(it)) },
                    onEssentials = { nav.navigate(SettingsRoutes.ESSENTIALS) })
            }
            composable(Tabs.ACTIVITY) {
                val vm: ActivityViewModel = viewModel(factory = viewModelFactory { initializer { ActivityViewModel(graph) } })
                val state by vm.state.collectAsStateWithLifecycle()
                ActivityScreen(state, vm, onSettings = { nav.navigate(SettingsRoutes.HOME) })
            }
            composable(
                EDITOR,
                arguments = listOf("kind", "id", "template", "start", "end", "days", "apps", "name").map { name ->
                    navArgument(name) { type = NavType.StringType; nullable = true; defaultValue = null }
                },
            ) {
                val vm: RuleEditorViewModel = viewModel(factory = viewModelFactory { initializer { RuleEditorViewModel(graph, createSavedStateHandle()) } })
                val state by vm.state.collectAsStateWithLifecycle()
                RuleEditorScreen(state, vm, onClose = { nav.popBackStack() })
            }
            composable(SettingsRoutes.HOME) {
                val state by settingsVm.state.collectAsStateWithLifecycle()
                SettingsHome(state, settingsVm, onBack = { nav.popBackStack() }, onOpen = { nav.navigate(it) })
            }
            composable(SettingsRoutes.HEALTH) {
                val state by settingsVm.state.collectAsStateWithLifecycle()
                HealthScreen(state, settingsVm, onBack = { nav.popBackStack() })
            }
            composable(SettingsRoutes.ESSENTIALS) {
                val state by settingsVm.state.collectAsStateWithLifecycle()
                EssentialsScreen(state, settingsVm, onBack = { nav.popBackStack() })
            }
            composable(SettingsRoutes.SETS) {
                val state by settingsVm.state.collectAsStateWithLifecycle()
                SavedSetsScreen(state, settingsVm, onBack = { nav.popBackStack() })
            }
            composable(SettingsRoutes.DEFAULT) {
                val state by settingsVm.state.collectAsStateWithLifecycle()
                DefaultBlockScreen(state, settingsVm, onBack = { nav.popBackStack() })
            }
            composable(SettingsRoutes.NOTIFICATIONS) {
                val state by settingsVm.state.collectAsStateWithLifecycle()
                NotificationsScreen(state, settingsVm, onBack = { nav.popBackStack() })
            }
            composable(SettingsRoutes.WIDGET) {
                val state by settingsVm.state.collectAsStateWithLifecycle()
                WidgetScreen(state, settingsVm, onBack = { nav.popBackStack() })
            }
            composable(SettingsRoutes.DATA) {
                val state by settingsVm.state.collectAsStateWithLifecycle()
                DataScreen(state, settingsVm, onBack = { nav.popBackStack() })
            }
            composable(SettingsRoutes.TROUBLESHOOTING) {
                val state by settingsVm.state.collectAsStateWithLifecycle()
                TroubleshootingScreen(state, settingsVm, onBack = { nav.popBackStack() }, onTested = { selectTab(nav, Tabs.BLOCK) })
            }
        }
    }
}

private fun selectTab(nav: NavHostController, route: String) {
    nav.navigate(route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** Three text + icon items; selected = accent text + small indicator line, no pill (spec 5.4). */
@Composable
private fun BottomBar(route: String?, onSelect: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Fb.bg).navigationBarsPadding()) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Fb.divider))
        Row(Modifier.fillMaxWidth()) {
            TabItem(stringResource(R.string.tab_block), painterResource(R.drawable.ic_notification), route == Tabs.BLOCK) { onSelect(Tabs.BLOCK) }
            TabItem(stringResource(R.string.tab_rules), rememberVectorPainter(Icons.Outlined.Schedule), route == Tabs.RULES) { onSelect(Tabs.RULES) }
            TabItem(stringResource(R.string.tab_activity), rememberVectorPainter(Icons.Outlined.BarChart), route == Tabs.ACTIVITY) { onSelect(Tabs.ACTIVITY) }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.TabItem(label: String, icon: Painter, selected: Boolean, onClick: () -> Unit) {
    val color = if (selected) Fb.accent else Fb.textSecondary
    Column(
        Modifier.weight(1f).heightIn(min = 64.dp).clickable(role = Role.Tab, onClick = onClick).semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.width(48.dp).height(2.dp).background(if (selected) Fb.accent else androidx.compose.ui.graphics.Color.Transparent))
        Spacer(Modifier.height(8.dp))
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, style = FbType.caption.copy(color = color))
        Spacer(Modifier.height(8.dp))
    }
}
