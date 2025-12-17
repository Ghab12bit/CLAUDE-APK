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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.ui.home.HomeScreen
import com.focusblock.app.ui.schedules.SchedulesScreen
import com.focusblock.app.ui.settings.SettingsScreen
import com.focusblock.app.ui.statistics.StatisticsScreen
import com.focusblock.app.ui.theme.BackgroundDark
import com.focusblock.app.ui.theme.CardDark
import com.focusblock.app.ui.theme.TextPrimary
import com.focusblock.app.ui.theme.TextSecondary
import com.focusblock.app.ui.theme.FocusBlockTheme
import com.focusblock.app.utils.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FocusBlockTheme {
                MainApp()
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

sealed class Screen(
    val route: String,
    val title: String,
    val selectedIcon: ImageVector,
    val unselectedIcon: ImageVector
) {
    data object Home : Screen(
        route = "home",
        title = "Blocking",
        selectedIcon = Icons.Filled.Shield,
        unselectedIcon = Icons.Outlined.Shield
    )
    data object Schedules : Screen(
        route = "schedules",
        title = "Schedules",
        selectedIcon = Icons.Filled.Schedule,
        unselectedIcon = Icons.Outlined.Schedule
    )
    data object Statistics : Screen(
        route = "statistics",
        title = "Statistics",
        selectedIcon = Icons.Filled.BarChart,
        unselectedIcon = Icons.Outlined.BarChart
    )
    data object Settings : Screen(
        route = "settings",
        title = "Profile",
        selectedIcon = Icons.Filled.Person,
        unselectedIcon = Icons.Outlined.Person
    )
}

val bottomNavItems = listOf(
    Screen.Home,
    Screen.Schedules,
    Screen.Statistics,
    Screen.Settings
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
                tonalElevation = androidx.compose.ui.unit.dp.times(0)
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
                            selectedIconColor = TextPrimary,
                            selectedTextColor = MaterialTheme.colorScheme.primary,
                            unselectedIconColor = TextSecondary,
                            unselectedTextColor = TextSecondary,
                            indicatorColor = MaterialTheme.colorScheme.primary
                        )
                    )
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Home.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Home.route) {
                HomeScreen()
            }
            composable(Screen.Schedules.route) {
                SchedulesScreen()
            }
            composable(Screen.Statistics.route) {
                StatisticsScreen()
            }
            composable(Screen.Settings.route) {
                SettingsScreen()
            }
        }
    }
}
