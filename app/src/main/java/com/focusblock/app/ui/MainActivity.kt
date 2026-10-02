package com.focusblock.app.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.OnboardingState
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.ui.focus.FocusExperienceScreen
import com.focusblock.app.ui.focus.GuardrailsScreen
import com.focusblock.app.ui.focus.PatternsScreen
import com.focusblock.app.ui.onboarding.OnboardingScreen
import com.focusblock.app.ui.schedules.SchedulesScreen
import com.focusblock.app.ui.settings.SettingsScreen
import com.focusblock.app.ui.theme.BackgroundDark
import com.focusblock.app.ui.theme.CardDark
import com.focusblock.app.ui.theme.TextPrimary
import com.focusblock.app.ui.theme.TextSecondary
import com.focusblock.app.ui.theme.FocusBlockTheme
import com.focusblock.app.utils.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_OPEN = "open"
        const val OPEN_END_SHEET = "end_sheet"
        const val OPEN_HEALTH = "health"
        const val OPEN_ACTIVITY = "activity"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FocusBlockTheme {
                MainAppWithOnboarding()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Start blocking service if permissions are granted
        val permissionStatus = PermissionUtils.getPermissionStatus(this)
        if (permissionStatus.hasRequiredPermissions) {
            AppBlockingService.start(this)
        }
    }
}

@Composable
fun MainAppWithOnboarding() {
    val context = LocalContext.current
    var hasCompletedOnboarding by remember { mutableStateOf<Boolean?>(null) }
    val coroutineScope = rememberCoroutineScope()

    // Check onboarding status
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            try {
                val db = FocusBlockDatabase.getInstance(context)
                val completed = db.focusBlockDao().hasCompletedOnboarding() ?: false
                withContext(Dispatchers.Main) {
                    hasCompletedOnboarding = completed
                }
            } catch (e: Exception) {
                // If error, assume not completed
                withContext(Dispatchers.Main) {
                    hasCompletedOnboarding = false
                }
            }
        }
    }

    when (hasCompletedOnboarding) {
        null -> {
            // Loading state
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = androidx.compose.ui.Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        false -> {
            com.focusblock.app.ui.block.BlockingFirstOnboarding(
                onComplete = {
                    coroutineScope.launch(Dispatchers.IO) {
                        try {
                            val db = FocusBlockDatabase.getInstance(context)
                            // Insert initial onboarding state
                            db.focusBlockDao().insertOnboardingState(
                                OnboardingState(
                                    id = 1,
                                    hasCompletedOnboarding = true,
                                    completedAt = System.currentTimeMillis()
                                )
                            )
                            withContext(Dispatchers.Main) {
                                hasCompletedOnboarding = true
                            }
                        } catch (e: Exception) {
                            // Just proceed anyway
                            withContext(Dispatchers.Main) {
                                hasCompletedOnboarding = true
                            }
                        }
                    }
                }
            )
        }
        true -> {
            var advanced by remember { mutableStateOf(false) }
            var advancedSettings by remember { mutableStateOf(false) }
            androidx.activity.compose.BackHandler(advanced) { advanced = false }
            if (advanced) {
                Column {
                    Row(Modifier.statusBarsPadding()) {
                        TextButton(onClick = { advanced = false }) { Text("Back") }
                        TextButton(onClick = { advancedSettings = false }) { Text("Protection") }
                        TextButton(onClick = { advancedSettings = true }) { Text("Settings") }
                    }
                    if (advancedSettings) SettingsScreen() else com.focusblock.app.ui.home.HomeScreen()
                }
            } else {
                com.focusblock.app.ui.block.BlockingFirstApp(onAdvanced = { advanced = true })
            }
        }
    }
}

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Focus : Screen(
        route = "focus",
        title = "Focus",
        selectedIcon = Icons.Filled.CenterFocusStrong,
        unselectedIcon = Icons.Outlined.CenterFocusStrong
    )
    data object Guardrails : Screen(
        route = "guardrails",
        title = "Guardrails",
        selectedIcon = Icons.Filled.AltRoute,
        unselectedIcon = Icons.Outlined.AltRoute
    )
    data object Patterns : Screen(
        route = "patterns",
        title = "Patterns",
        selectedIcon = Icons.Filled.QueryStats,
        unselectedIcon = Icons.Outlined.QueryStats
    )
    data object Settings : Screen(
        route = "settings",
        title = "Setup",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person
    )
}

val bottomNavItems = listOf(
    Screen.Focus,
    Screen.Guardrails,
    Screen.Patterns
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        containerColor = BackgroundDark,
        bottomBar = {
            NavigationBar(
                containerColor = BackgroundDark,
                tonalElevation = 0.dp
            ) {
                bottomNavItems.forEach { screen ->
                    val selected = currentDestination?.hierarchy?.any { it.route == screen.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                imageVector = if (selected) screen.selectedIcon else screen.unselectedIcon,
                                contentDescription = screen.title
                            )
                        },
                        label = { Text(screen.title) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = MaterialTheme.colorScheme.primary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = Color.Transparent
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Focus.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Focus.route) {
                FocusExperienceScreen(
                    onOpenSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.Guardrails.route) {
                GuardrailsScreen(
                    onOpenScheduleEditor = { navController.navigate("schedule_editor") },
                    onOpenProtectionSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.Patterns.route) {
                PatternsScreen(
                    onOpenFocus = {
                        navController.navigate(Screen.Focus.route) {
                            popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                            launchSingleTop = true
                        }
                    }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
            composable("schedule_editor") {
                SchedulesScreen()
            }
        }
    }
}
