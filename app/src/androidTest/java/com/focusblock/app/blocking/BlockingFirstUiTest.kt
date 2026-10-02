package com.focusblock.app.blocking

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.OnboardingState
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.database.entity.QuickBlockSession
import com.focusblock.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.Rule
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BlockingFirstUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Before fun prepare() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        listOf("appops set com.focusblock.app GET_USAGE_STATS allow", "appops set com.focusblock.app SYSTEM_ALERT_WINDOW allow",
            "pm grant com.focusblock.app android.permission.POST_NOTIFICATIONS",
            "settings put secure enabled_accessibility_services com.focusblock.app/com.focusblock.app.service.FocusBlockAccessibilityService",
            "settings put secure accessibility_enabled 1").forEach { command ->
            automation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
        }
        runBlocking {
            val db = FocusBlockDatabase.getDatabase(context)
            db.quickBlockSessionDao().deactivateAll()
            db.settingsDao().insert(AppSettings("quick_block_saved_apps", ""))
            db.settingsDao().insert(AppSettings(BlockSessionStore.LAST, "{}"))
            db.focusBlockDao().insertOnboardingState(OnboardingState(id = 1, hasCompletedOnboarding = true, completedAt = System.currentTimeMillis()))
        }
    }
    @Test fun startingBlockInterceptsAnInstalledApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = FocusBlockDatabase.getDatabase(context)
        val app = com.focusblock.app.utils.AppUtils.getInstalledApps(context, true).first {
            it.packageName !in BlockSessionStore.requiredPackages(context) &&
                (it.packageName.contains("calendar") || it.packageName.contains("calculator") || it.packageName.contains("browser"))
        }
        runBlocking { db.settingsDao().insert(AppSettings("quick_block_saved_apps", app.packageName)) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Apps to block (1)").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(15000) { compose.onAllNodesWithText("Start 45-minute block").fetchSemanticsNodes().any { node -> !node.config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled) } }
            screenshot(context, "block-selected")
            compose.onNodeWithText("Start 45-minute block").performScrollTo().performClick()
            compose.waitUntil(10000) { runBlocking { db.quickBlockSessionDao().getActiveSessionSync() != null } }
            val since = System.currentTimeMillis()
            context.startActivity(context.packageManager.getLaunchIntentForPackage(app.packageName)!!.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            // Home is briefly foregrounded before the intervention is attached.
            // No Compose root during that transition is expected, not a failure.
            compose.waitUntil(15000) {
                compose.onAllNodesWithText("${app.appName} is paused.")
                    .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
            }
            screenshot(context, "intervention")
            org.junit.Assert.assertTrue(runBlocking { db.blockLogDao().getAllLogs().first().any { log -> log.packageName == app.packageName && log.timestamp >= since } })
            compose.onNodeWithText("Back to home screen").performClick()
        }
    }
    @Test fun nativeNavigationAndIntegratedPicker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking { FocusBlockDatabase.getDatabase(context).focusBlockDao().insertOnboardingState(OnboardingState(id = 1, hasCompletedOnboarding = true, completedAt = System.currentTimeMillis())) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Apps to block (0)").fetchSemanticsNodes().isNotEmpty() }
            screenshot(context, "block")
            compose.onNodeWithText("Edit selection").performClick()
            compose.onNodeWithText("Search apps").assertExists()
            screenshot(context, "app-picker")
            compose.onNodeWithText("Search apps").performTextInput("Calendar")
            compose.onNode(hasText("Calendar") and !hasSetTextAction()).performClick()
            screenshot(context, "picker-filtered")
            compose.onNodeWithText("Use 1 apps").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Apps to block (1)").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Rules", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Automatic blocking.").assertExists()
            screenshot(context, "rules")
            compose.onNodeWithText("Add a rule").performClick()
            compose.onNodeWithText("Schedule").performClick()
            screenshot(context, "rule-editor")
            androidx.test.espresso.Espresso.pressBack()
            compose.onNodeWithText("Activity", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Your usage, clearly.").assertExists()
            screenshot(context, "activity")
            compose.onAllNodesWithText("What needs your attention?").assertCountEquals(0)
            compose.onNodeWithContentDescription("Settings and permissions").performClick()
            screenshot(context, "settings")
            compose.onNodeWithText("Permissions").performClick()
            screenshot(context, "permissions")
        }
    }
    @Test fun activeAndCompletionUsePersistedState() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val db = FocusBlockDatabase.getDatabase(context)
        val now = System.currentTimeMillis()
        val id = runBlocking {
            val id = db.quickBlockSessionDao().insert(QuickBlockSession(startTime = now, endTime = now + 45 * 60000, blockedPackages = "com.android.chrome"))
            db.settingsDao().insert(AppSettings(BlockSessionStore.KEY, BlockSessionMetadata(id = id, strict = true).json()))
            id
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Add 15 minutes").fetchSemanticsNodes().isNotEmpty() }
            screenshot(context, "active")
            compose.onNodeWithText("Add 15 minutes").performScrollTo().performClick()
            compose.waitUntil(5000) { runBlocking { (db.quickBlockSessionDao().getActiveSessionSync()?.endTime ?: 0) > now + 45 * 60000 } }
            runBlocking { db.quickBlockSessionDao().deactivate(id) }
            compose.waitUntil(5000) { compose.onAllNodesWithText("Block ended").fetchSemanticsNodes().isNotEmpty() }
            screenshot(context, "completion")
        }
    }
    private fun screenshot(context: Context, name: String) {
        compose.waitForIdle()
        // AGP may uninstall the test application after instrumentation. Keep
        // emulator-only evidence outside private app data so CI can retrieve it.
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().getUiAutomation(android.app.UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        listOf("mkdir -p /sdcard/Download/focusblock-screenshots", "screencap -p /sdcard/Download/focusblock-screenshots/$name.png").forEach { command ->
            automation.executeShellCommand(command).use {
                android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }
}
