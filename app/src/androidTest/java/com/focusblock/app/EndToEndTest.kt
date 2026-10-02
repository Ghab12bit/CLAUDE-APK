package com.focusblock.app

import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.BlockSetup
import com.focusblock.app.core.EndResult
import com.focusblock.app.core.ServiceHeartbeat
import com.focusblock.app.core.StartRequest
import com.focusblock.app.core.StartResult
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.database.entity.UnlockEventEntity
import com.focusblock.app.policy.SessionType
import com.focusblock.app.policy.Strength
import com.focusblock.app.ui.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end checks on a real Android 14 emulator with the accessibility service enabled:
 * opening a blocked app shows the block screen, Strict Lock removes every ordinary bypass, Open
 * anyway is time-boxed and logged, and the app's tabs render from persisted state.
 */
@RunWith(AndroidJUnit4::class)
class EndToEndTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val device: UiDevice = run {
        // UiAutomator's default connection suspends every other accessibility service, which
        // would switch FocusBlock's own service off mid-test. Keep it running.
        Configurator.getInstance().setUiAutomationFlags(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        UiDevice.getInstance(instrumentation)
    }
    private val graph = AppGraph.get(context)
    private lateinit var target: String
    private lateinit var targetName: String

    private fun shell(command: String): String =
        instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).executeShellCommand(command).let {
            ParcelFileDescriptor.AutoCloseInputStream(it).use { stream -> String(stream.readBytes()) }
        }

    /** What is on screen, for failure messages. */
    private fun screenState(): String {
        val texts = device.findObjects(By.text(java.util.regex.Pattern.compile(".+", java.util.regex.Pattern.DOTALL))).mapNotNull { it.text?.replace('\n', ' ') }.take(40)
        val decision = runCatching { runBlocking { graph.enforcer.decide(target).first } }.getOrNull()
        return "package=${device.currentPackageName} service=${ServiceHeartbeat.connected} blocked=${decision?.blocked} reason=${decision?.primary} texts=$texts"
    }

    private fun check(condition: Boolean, what: String) = assertTrue("$what | ${screenState()}", condition)

    @Before fun prepare() {
        listOf(
            "appops set ${context.packageName} GET_USAGE_STATS allow",
            "appops set ${context.packageName} SYSTEM_ALERT_WINDOW allow",
            "pm grant ${context.packageName} android.permission.POST_NOTIFICATIONS",
        ).forEach(::shell)
        // Writing the setting again makes Android re-bind the service, which drops events
        // mid-test. Only write it when it is not already enabled.
        val component = "${context.packageName}/com.focusblock.app.service.FocusBlockAccessibilityService"
        if (!shell("settings get secure enabled_accessibility_services").contains(component)) {
            shell("settings put secure enabled_accessibility_services $component")
            shell("settings put secure accessibility_enabled 1")
        }
        runBlocking {
            graph.db.settingsDao().insert(AppSettings(PrefKeys.ONBOARDING_DONE, "true"))
            graph.db.openHelper.writableDatabase.execSQL("UPDATE block_sessions SET isActive = 0, endReason = 'ENDED_EARLY', outcome = 'ENDED_EARLY' WHERE isActive = 1")
            graph.db.openHelper.writableDatabase.execSQL("DELETE FROM unlock_events")
            graph.essentials.ensureSeeded()
            graph.policy.invalidate()
            val exempt = graph.policy.exempt()
            val preferred = listOf("calendar", "contacts", "documentsui", "gallery", "calculator", "chrome", "browser")
            val apps = graph.apps.all().filter { it.packageName !in exempt }
            val app = preferred.firstNotNullOfOrNull { key -> apps.firstOrNull { it.packageName.contains(key) } } ?: apps.first()
            target = app.packageName
            targetName = app.label
        }
        // Wait for Android to bind the accessibility service.
        val deadline = System.currentTimeMillis() + 15_000
        while (!ServiceHeartbeat.connected && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertTrue("Accessibility service did not connect", ServiceHeartbeat.connected)
    }

    @After fun cleanUp() {
        runBlocking { graph.db.openHelper.writableDatabase.execSQL("UPDATE block_sessions SET isActive = 0 WHERE isActive = 1") }
        device.pressHome()
    }

    private fun start(strength: Strength, minutes: Int = 30) = runBlocking {
        val r = graph.sessions.start(StartRequest(BlockSetup(listOf(target), SessionType.TIMED, minutes, strength = strength), intention = "Finish the Q3 report"))
        assertTrue("Block did not start: $r", r is StartResult.Started)
    }

    private fun openTarget() {
        val intent = context.packageManager.getLaunchIntentForPackage(target)!!.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        context.startActivity(intent)
    }

    private fun waitForBlockScreen() {
        val shown = device.wait(Until.hasObject(By.textContains("is blocked until")), 20_000)
        screenshot("intervention-$target-${System.currentTimeMillis() % 100000}")
        check(shown, "Block screen did not appear for $target")
    }

    @Test fun openingABlockedAppShowsTheBlockScreenAndIsLogged() {
        val since = System.currentTimeMillis()
        start(Strength.NORMAL)
        openTarget()
        waitForBlockScreen()
        assertNotNull(device.findObject(By.text("Blocked by your 30-min block")))
        assertNotNull(device.findObject(By.text("You're working on: Finish the Q3 report")))
        assertNotNull(device.findObject(By.textContains("try in this block")))
        val logs = runBlocking { graph.db.attemptDao().since(since) }
        assertTrue(logs.any { it.packageName == target && it.attemptNumber >= 1 && it.strength == Strength.NORMAL.name })
        device.findObject(By.text("Back to home screen")).click()
        device.waitForIdle()
    }

    @Test fun strictLockShowsOnlyEmergencyAccessAndCannotBeEnded() {
        start(Strength.STRICT)
        assertEquals(EndResult.STRICT_LOCKED, runBlocking { graph.sessions.end() })
        openTarget()
        waitForBlockScreen()
        assertNull("Strict Lock must not offer Open anyway", device.findObject(By.textStartsWith("Open anyway")))
        assertNull(device.findObject(By.textContains("Need access")))
        assertNotNull(device.findObject(By.text("Emergency access")))
        assertNotNull(device.findObject(By.text("10-min wait · reason required")))
        // The override path itself refuses too, whatever the UI shows.
        val refused = runBlocking { graph.overrides.openAnyway(target, 0) }
        assertTrue(refused is com.focusblock.app.core.OverrideManager.Result.NotAllowed)
    }

    @Test fun openAnywayOnAFirstAttemptIsTimeBoxedAndLogged() {
        start(Strength.NORMAL)
        openTarget()
        waitForBlockScreen()
        device.findObject(By.text("Open anyway")).click()
        val unlocked = device.wait(Until.gone(By.textContains("is blocked until")), 10_000)
        screenshot("open-anyway")
        check(unlocked, "Open anyway did not open the app")
        val events = runBlocking { graph.db.unlockEventDao().all() }
        val grant = events.first { it.packageName == target && it.status == UnlockEventEntity.GRANTED }
        assertEquals("OPEN_ANYWAY", grant.kind)
        assertTrue((grant.expiresAt ?: 0) - (grant.grantedAt ?: 0) <= 5 * 60_000L)
        val decision = runBlocking { graph.enforcer.decide(target).first }
        assertTrue("App should be open for the override window", !decision.blocked)
    }

    @Test fun tabsRenderFromPersistedState() {
        start(Strength.NORMAL, minutes = 45)
        openMain()
        check(device.wait(Until.hasObject(By.textStartsWith("Blocking until")), 15_000), "Active block title")
        check(device.hasObject(By.text("“Finish the Q3 report”")), "Intention line")
        screenshot("01-block-active")
        scrollTo("End block early")
        check(device.wait(Until.hasObject(By.text("+15 min")), 5_000), "+15 min")
        check(device.hasObject(By.text("End block early")), "End block early link")
        screenshot("02-block-active-actions")
        device.findObject(By.text("Rules")).click()
        check(device.wait(Until.hasObject(By.text("What runs automatically")), 10_000), "Rules tab")
        screenshot("05-rules")
        device.findObject(By.text("Activity")).click()
        check(device.wait(Until.hasObject(By.text("SCREEN TIME")), 15_000), "Activity tab")
        screenshot("06-activity-day")
        repeat(4) { i ->
            scrollDown()
            screenshot("07-activity-day-${i + 1}")
        }
        check(device.hasObject(By.text("Blocks")) || device.hasObject(By.text("Distractions")), "Activity sections")
        device.findObject(By.text("Week"))?.let {
            scrollToTop()
            device.findObject(By.text("Week")).click()
            device.wait(Until.hasObject(By.text("LAST 7 DAYS")), 10_000)
            screenshot("08-activity-week")
        }
        device.findObject(By.text("Trend"))?.let {
            it.click()
            device.wait(Until.hasObject(By.text("LAST 4 WEEKS")), 10_000)
            screenshot("09-activity-trend")
        }
        scrollToTop()
        device.findObject(By.desc("Settings")).click()
        check(device.wait(Until.hasObject(By.text("Blocking health")), 10_000), "Settings")
        screenshot("10-settings")
    }

    @Test fun endingANormalBlockEarlyNeedsAWaitAndAHold() {
        start(Strength.NORMAL, minutes = 45)
        openMain()
        check(device.wait(Until.hasObject(By.textStartsWith("Blocking until")), 15_000), "Active block title")
        scrollTo("End block early")
        check(device.wait(Until.hasObject(By.text("End block early")), 5_000), "End block early link")
        device.findObject(By.text("End block early")).click()
        check(device.wait(Until.hasObject(By.text("Keep blocking")), 5_000), "End sheet")
        check(device.hasObject(By.textStartsWith("You can end it in")), "Wait before ending")
        assertNull("Ending must not be offered straight away", device.findObject(By.text("Hold to end block")))
        screenshot("03-end-early-wait")
        check(device.wait(Until.hasObject(By.text("Hold to end block")), 15_000), "Hold button after the wait")
        screenshot("04-end-early-hold")
        // A quick tap does nothing.
        device.findObject(By.text("Hold to end block")).click()
        Thread.sleep(500)
        assertNotNull("A tap must not end the block", runBlocking { graph.sessions.active() })
        // Holding for 3.5 s ends it (swipe in place: 700 steps of ~5 ms).
        val hold = device.findObject(By.text("Hold to end block")).visibleCenter
        device.swipe(hold.x, hold.y, hold.x, hold.y, 700)
        val deadline = System.currentTimeMillis() + 5_000
        while (runBlocking { graph.sessions.active() } != null && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertNull("Holding should end the block", runBlocking { graph.sessions.active() })
    }

    @Test fun appsCanBeAddedToARunningStrictBlock() {
        start(Strength.STRICT)
        val other = runBlocking {
            val exempt = graph.policy.exempt()
            graph.apps.all().map { it.packageName }.first { it != target && it !in exempt }
        }
        val added = runBlocking { graph.sessions.addApps(listOf(other, target)) }
        assertEquals(1, added)
        val packages = runBlocking { graph.sessions.active()!!.packages }
        assertTrue(packages.contains(target) && packages.contains(other))
        val decision = runBlocking { graph.enforcer.decide(other).first }
        assertTrue("A newly added app is blocked at once", decision.blocked)
        openMain()
        check(device.wait(Until.hasObject(By.textStartsWith("Blocking until")), 15_000), "Active block title")
        device.findObject(By.text("Add apps"))?.click()
        check(device.wait(Until.hasObject(By.text("Add apps to this block")), 5_000), "Add apps picker")
        screenshot("11-add-apps-picker")
    }

    @Test fun theFirstRunTestBlockLeavesTheChosenAppsAlone() {
        val chosen = runBlocking {
            val exempt = graph.policy.exempt()
            graph.apps.all().map { it.packageName }.filter { it !in exempt }.take(3)
        }
        runBlocking {
            graph.sessions.saveSelection(chosen)
            val r = graph.sessions.start(StartRequest(BlockSetup(listOf(chosen.first()), SessionType.TIMED, 1), test = true))
            assertTrue(r is StartResult.Started)
            assertEquals(chosen, graph.sessions.lastSetup()!!.packages)
            graph.sessions.discardTest((r as StartResult.Started).id)
            assertNull(graph.sessions.active())
            assertNull("The test block never asks Did you finish?", graph.db.blockSessionDao().awaitingOutcome())
            assertEquals(chosen, graph.sessions.savedSelection())
        }
    }

    @Test fun idleBlockTabShowsSetupWithoutGiantNumber() {
        runBlocking { graph.sessions.saveSelection(listOf(target)) }
        openMain()
        check(device.wait(Until.hasObject(By.text("Block distractions")), 15_000), "Idle Block tab")
        assertNotNull(device.findObject(By.text("What are you working on?")))
        assertNotNull(device.findObject(By.text("Intervals")))
        assertNotNull(device.findObject(By.text("Until I stop")))
        assertNull(device.findObject(By.text("Cycles")))
        screenshot("00-block-idle")
        scrollDown()
        screenshot("00-block-idle-2")
    }

    private fun openMain() {
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
    }

    private fun scrollDown() {
        device.findObject(By.scrollable(true).pkg(context.packageName))?.scroll(Direction.DOWN, 0.8f)
        device.waitForIdle()
    }

    private fun scrollTo(text: String) {
        repeat(5) {
            if (device.hasObject(By.text(text))) return
            scrollDown()
        }
    }

    private fun scrollToTop() {
        repeat(6) { device.findObject(By.scrollable(true).pkg(context.packageName))?.scroll(Direction.UP, 1f) }
        device.waitForIdle()
    }

    private fun screenshot(name: String) {
        runCatching {
            device.waitForIdle()
            val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot() ?: return
            val file = File(context.getExternalFilesDir("screenshots"), "$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
