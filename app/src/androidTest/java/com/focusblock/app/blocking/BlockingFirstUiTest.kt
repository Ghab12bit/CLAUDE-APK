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
import com.focusblock.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class BlockingFirstUiTest {
    @get:Rule val compose = createEmptyComposeRule()
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
    private fun screenshot(context: Context, name: String) {
        compose.waitForIdle()
        val file = File(context.filesDir, "screenshots/$name.png")
        file.parentFile?.mkdirs()
        file.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
