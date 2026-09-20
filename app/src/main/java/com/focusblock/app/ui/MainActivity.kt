package com.focusblock.app.ui

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.OnboardingState
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.ui.now.NowScreen
import com.focusblock.app.ui.setup.SetupScreen
import com.focusblock.app.ui.routines.RoutinesScreen
import com.focusblock.app.ui.settings.ProfileScreen
import com.focusblock.app.ui.statistics.StatisticsScreen
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.PermissionUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * On Android 13+ POST_NOTIFICATIONS must be requested at runtime. Without
     * it the foreground-service notification is suppressed: blocking still
     * runs, but the user loses the only persistent signal that it is running,
     * which for this app is most of the reassurance. It was declared in the
     * manifest and checked in PermissionUtils but never actually requested.
     */
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* status is re-read by the UI */ }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        if (PermissionUtils.hasNotificationPermission(this)) return
        notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermissionIfNeeded()

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
            SetupScreen(
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
            MainApp()
        }
    }
}

/**
 * Where the app can be.
 *
 * Two destinations, not four.
 *
 * The old bar was Blocking / Routines / Insights / Setup, in that order, with a
 * pill behind the active item -- which is AppBlock's Blocking / Strict Mode /
 * Insights / Profile with two words changed. Same count, same positions, same
 * treatment. No amount of recolouring inside those four tabs was ever going to
 * stop the app reading as a copy, because the first thing anyone sees is the
 * shape of the navigation.
 *
 * So: NOW is where you are, PLAN is what happens without you. Review is not a
 * destination -- it is the lower half of NOW, because a whole screen reserved
 * for metrics is what turns a blocker into a dashboard, and a dashboard is the
 * thing that has to be filled with grades and percentages. Settings is a sheet
 * from the header, because permissions and an allowlist are not a place you go.
 */
sealed class Screen(val route: String, val title: String) {
    data object Now : Screen(route = "now", title = "Now")
    data object Plan : Screen(route = "plan", title = "Plan")

    /** Reached from the NOW header, never from a bar. */
    data object Settings : Screen(route = "settings", title = "Settings")

    /** The long view, reached from Today. Not a destination. */
    data object History : Screen(route = "history", title = "History")
}

val bottomNavItems = listOf(Screen.Now, Screen.Plan)

/**
 * The switch between NOW and PLAN.
 *
 * A two-item text switch on the ground itself rather than a Material
 * NavigationBar: no container, no pill, no icons. The active destination is
 * stated by weight and by a short Ember underline that sits directly beneath
 * the word, which is a mark this app can own. It also takes about a third of
 * the vertical space a NavigationBar does, which the NOW screen spends on
 * content instead.
 */
@Composable
private fun Destinations(
    current: String?,
    onSelect: (Screen) -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Ground)
            .padding(horizontal = 24.dp)
            .padding(top = 10.dp, bottom = 22.dp),
        horizontalArrangement = Arrangement.spacedBy(28.dp)
    ) {
        bottomNavItems.forEach { screen ->
            val selected = current == screen.route
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onSelect(screen) }
                    .padding(horizontal = 4.dp, vertical = 8.dp)
            ) {
                Text(
                    screen.title,
                    color = if (selected) Ink else InkFaint,
                    fontSize = 16.sp,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
                )
                Spacer(Modifier.height(6.dp))
                Box(
                    Modifier
                        .width(if (selected) 18.dp else 0.dp)
                        .height(2.dp)
                        .background(Ember)
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    Scaffold(
        containerColor = Ground,
        bottomBar = {
            // Settings is not a destination, so it is not in here. It opens
            // from the NOW header, where the state that would send you looking
            // for it already is.
            if (currentDestination?.route in listOf(Screen.Now.route, Screen.Plan.route)) {
                Destinations(currentDestination?.route) { screen ->
                    navController.navigate(screen.route) {
                        popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Now.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Now.route) {
                NowScreen(
                    onOpenRoutines = { navController.navigate(Screen.Plan.route) },
                    onFixPermissions = { navController.navigate(Screen.Settings.route) },
                    onEditApps = { navController.navigate(Screen.Settings.route) },
                    onOpenSettings = { navController.navigate(Screen.Settings.route) },
                    onOpenHistory = { navController.navigate(Screen.History.route) }
                )
            }
            composable(Screen.Plan.route) {
                RoutinesScreen()
            }
            // Not a destination in the bar, but not deleted either: the full
            // history stays reachable from the Today card until the narrative
            // review on NOW covers everything it showed.
            composable(Screen.History.route) {
                StatisticsScreen()
            }
            composable(Screen.Settings.route) {
                ProfileScreen(onClose = { navController.popBackStack() })
            }
        }
    }
}
