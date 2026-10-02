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
        val automation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        listOf("appops set com.focusblock.app GET_USAGE_STATS allow", "appops set com.focusblock.app SYSTEM_ALERT_WINDOW allow").forEach { command ->
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
    @Test fun nativeNavigationAndIntegratedPicker() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        runBlocking { FocusBlockDatabase.getDatabase(context).focusBlockDao().insertOnboardingState(OnboardingState(id = 1, hasCompletedOnboarding = true, completedAt = System.currentTimeMillis())) }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.waitUntil(15000) { compose.onAllNodesWithText("Apps to block (0)").fetchSemanticsNodes().isNotEmpty() }
            screenshot(context, "block")
            compose.onNodeWithText("Edit selection").performClick()
            compose.onNodeWithText("Search apps").assertExists()
            screenshot(context, "app-picker")
            compose.onNodeWithText("Use 0 apps").performClick()
            compose.onNodeWithText("Rules", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Automatic blocking.").assertExists()
            screenshot(context, "rules")
            compose.onNodeWithText("Activity", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Your usage, clearly.").assertExists()
            screenshot(context, "activity")
            compose.onAllNodesWithText("What needs your attention?").assertCountEquals(0)
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
        val file = File(context.filesDir, "screenshots/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
