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
import com.focusblock.app.policy.UsageCalculator
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
    companion object {
        /** Readable by `adb pull` after the run; the app's own folders are removed when it is uninstalled. */
        const val SHOTS = "/data/local/tmp/focusblock-screens"
    }

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
            // A stopped quick block waits for "Did you finish?"; settle it so that sheet never covers a later test.
            graph.db.openHelper.writableDatabase.execSQL("UPDATE block_sessions SET outcome = 'UNANSWERED' WHERE isActive = 0 AND outcome IS NULL")
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
        assertNotNull(device.findObject(By.text("Part of your 30-min block")))
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

    @Test fun aForgottenEmergencyRequestIsStillReadyLater() {
        start(Strength.NORMAL)
        val now = System.currentTimeMillis()
        // Ready 90 minutes ago: the old rule dropped a request an hour after its wait and made the
        // user wait again. Never before today, because a request is kept until midnight.
        val readyAt = maxOf(com.focusblock.app.policy.PolicyTime.startOfDay(now, graph.clock.zone()) + 60_000L, now - 90 * 60_000L)
        runBlocking {
            graph.db.unlockEventDao().insert(
                UnlockEventEntity(packageName = target, appName = targetName, kind = "EMERGENCY", status = UnlockEventEntity.PENDING,
                    requestedAt = readyAt - 10 * 60_000L, readyAt = readyAt, reasonText = "Need the boarding pass", strength = Strength.NORMAL.name),
            )
        }
        openTarget()
        waitForBlockScreen()
        scrollTo("Open $targetName for 5 minutes")
        check(device.hasObject(By.textStartsWith("Your wait is done")), "Ready emergency access is offered without a new wait")
        screenshot("16-emergency-ready")
        tap("Open $targetName for 5 minutes")
        check(device.wait(Until.hasObject(By.pkg(target).depth(0)), 15_000), "$targetName opened with emergency access")
        val granted = runBlocking { graph.db.unlockEventDao().activeOverrides(System.currentTimeMillis()) }
        assertTrue(granted.any { it.packageName == target && it.kind == "EMERGENCY" })
    }

    @Test fun theBlockScreenOffersNoOpenAnywayOnlyEndingTheBlock() {
        start(Strength.NORMAL)
        openTarget()
        waitForBlockScreen()
        assertNull("Open anyway was removed", device.findObject(By.textStartsWith("Open anyway")))
        assertTrue(runBlocking { graph.overrides.openAnyway(target, 0) } is com.focusblock.app.core.OverrideManager.Result.NotAllowed)
        screenshot("12-block-screen-no-open-anyway")
        // "End this block early…" leads to the end-early sheet (wait and hold), not straight into the app.
        tap("End this block early…")
        check(device.wait(Until.hasObject(By.text("Keep blocking")), 15_000), "End-early sheet from the block screen")
        assertNotNull("Nothing ends until the hold", runBlocking { graph.sessions.active() })
        assertTrue(runBlocking { graph.enforcer.decide(target).first }.blocked)
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
        tap("Rules")
        check(device.wait(Until.hasObject(By.text("What runs automatically")), 10_000), "Rules tab")
        screenshot("05-rules")
        scrollDown()
        screenshot("05-rules-2")
        tap("Activity")
        check(device.wait(Until.hasObject(By.text("SCREEN TIME")), 15_000), "Activity tab")
        screenshot("06-activity-day")
        tap("Week")
        check(device.wait(Until.hasObject(By.text("LAST 7 DAYS")), 10_000), "Week view")
        screenshot("08-activity-week")
        tap("Trend")
        check(device.wait(Until.hasObject(By.text("LAST 4 WEEKS")), 10_000), "Trend view")
        screenshot("09-activity-trend")
        tap("Day")
        check(device.wait(Until.hasObject(By.text("TODAY")), 10_000), "Day view")
        repeat(4) { i ->
            scrollDown()
            screenshot("07-activity-day-${i + 1}")
        }
        check(device.hasObject(By.text("Blocks")) || device.hasObject(By.text("Distractions")), "Activity sections")
        // Back to an unscrolled tab: scrolling up from the top edge would open the notification shade.
        tap("Rules")
        val settings = device.wait(Until.findObject(By.desc("Settings")), 5_000)
        check(settings != null, "Settings button")
        settings.click()
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

    @Test fun notificationAndWidgetEndOpenTheEndEarlySheet() {
        start(Strength.NORMAL, minutes = 45)
        // The notification's and widget's "End block early" open the app like this; nothing ends yet.
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(MainActivity.EXTRA_OPEN, MainActivity.OPEN_END_EARLY),
        )
        check(device.wait(Until.hasObject(By.text("Keep blocking")), 15_000), "End-early sheet from the notification")
        assertNotNull("Opening the sheet must not end the block", runBlocking { graph.sessions.active() })
        device.findObject(By.text("Keep blocking")).click()
        check(device.wait(Until.gone(By.text("Keep blocking")), 5_000), "Keep blocking closes the sheet")
        assertNotNull(runBlocking { graph.sessions.active() })
    }

    @Test fun anAppCanBeLeftOutOfScreenTimeAndCountedAgain() {
        runBlocking {
            graph.history.setCounted(target, targetName, false)
            assertTrue(target in graph.history.notCounted())
            graph.history.setCounted(target, targetName, true)
            assertTrue(target !in graph.history.notCounted())
        }
    }

    @Test fun timeOnASecondScreenOfAnAppIsCounted() {
        val settings = "com.android.settings"
        fun used() = runBlocking { graph.usage.invalidate(); graph.usage.todayTotals(0)?.get(settings) ?: 0L }
        val before = used()
        val opened = System.currentTimeMillis()
        context.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        check(device.wait(Until.hasObject(By.pkg(settings).depth(0)), 10_000), "Settings opened")
        Thread.sleep(2_000)
        // Another screen (activity) of the same app: Android reports the first screen as stopped
        // after this one resumed, which must not end the app's time.
        context.startActivity(Intent(android.provider.Settings.ACTION_DISPLAY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        Thread.sleep(20_000)
        check(device.currentPackageName == settings, "Still in Settings")
        device.pressHome()
        Thread.sleep(1_000)
        val counted = used() - before
        // The previous way of counting (any stop ended the app's time) on the same events.
        val events = runBlocking { graph.usage.events(opened - 5_000, System.currentTimeMillis()) }.orEmpty()
        val oldWay = events.map { if (it.type == UsageCalculator.Type.STOPPED) it.copy(type = UsageCalculator.Type.PAUSED, cls = null) else it.copy(cls = null) }
        val oldCounted = UsageCalculator.totals(UsageCalculator.intervals(oldWay, System.currentTimeMillis()), opened - 5_000, System.currentTimeMillis())[settings] ?: 0L
        assertTrue("Counted ${counted / 1000} s of about 22 s in Settings (old way: ${oldCounted / 1000} s)", counted >= 20_000)
        assertTrue("The old way should have missed the second screen, counted ${oldCounted / 1000} s", oldCounted < 10_000)
    }

    @Test fun aQuickBlockRunsUntilStoppedWithOneConfirmation() {
        runBlocking { graph.sessions.saveSelection(listOf(target)) }
        openMain()
        // The card is one clickable element, so its texts are read together.
        val card = device.wait(Until.findObject(By.textStartsWith("Quick block")), 15_000)
        check(card != null, "Quick block card")
        screenshot("13-quick-block-card")
        card.click()
        check(device.wait(Until.hasObject(By.text("Quick block on")), 10_000), "Quick block running")
        val running = runBlocking { graph.sessions.active() }
        assertNotNull(running)
        assertEquals("INDEFINITE", running!!.sessionType)
        assertNull("A quick block has no end time", running.plannedEndAt)
        assertTrue(runBlocking { graph.enforcer.decide(target).first }.blocked)
        screenshot("14-quick-block-running")
        scrollTo("Stop quick block")
        tap("Stop quick block")
        check(device.wait(Until.hasObject(By.text("Stop quick block?")), 5_000), "Stop confirmation")
        tap("Stop")
        val deadline = System.currentTimeMillis() + 5_000
        while (runBlocking { graph.sessions.active() } != null && System.currentTimeMillis() < deadline) Thread.sleep(200)
        assertNull("Stop ends the quick block", runBlocking { graph.sessions.active() })
        // Stopping is how a quick block completes: it asks how it went instead of counting as ended early.
        check(device.wait(Until.hasObject(By.text("Did you finish?")), 5_000), "Did you finish? after stopping")
        tap("Finished")
        val answered = System.currentTimeMillis() + 5_000
        while (runBlocking { graph.db.blockSessionDao().get(running.id) }?.outcome == null && System.currentTimeMillis() < answered) Thread.sleep(200)
        assertEquals("FINISHED", runBlocking { graph.db.blockSessionDao().get(running.id) }?.outcome)
    }

    @Test fun aLimitPageShowsTodayHowItWorksAndTheWeek() {
        val id = runBlocking {
            graph.db.appLimitDao().upsert(com.focusblock.app.database.entity.AppLimitEntity(name = "Social apps", packages = target, minutesPerDay = 30))
        }
        try {
            openMain()
            tap("Rules")
            // A rule card is one clickable element, so its texts are read together.
            val row = device.wait(Until.findObject(By.textStartsWith("Social apps")), 10_000)
            check(row != null, "Limit card on Rules")
            row.click()
            check(device.wait(Until.hasObject(By.text("How it works")), 10_000), "How it works")
            check(device.hasObject(By.text("TODAY")), "Today card")
            screenshot("15-limit-page")
            // With "How it works" folded on a saved limit, the week card's heading is on the first screen;
            // scrollTo only scrolls when it is not.
            scrollTo("Last 7 days")
            check(device.hasObject(By.text("Last 7 days")), "Week card")
            scrollDown()
            screenshot("15-limit-page-2")
            scrollTo("Delete limit")
            screenshot("15-limit-page-3")
            check(device.hasObject(By.text("Limit is on")), "On/off switch")
        } finally {
            runBlocking { graph.db.appLimitDao().delete(id) }
        }
    }

    @Test fun essentialAppsAreNotCountedInALimit() {
        val essential = runBlocking { graph.policy.exempt().first { it != target } }
        val id = runBlocking {
            graph.db.appLimitDao().upsert(com.focusblock.app.database.entity.AppLimitEntity(name = "Mixed", packages = "$target,$essential", minutesPerDay = 30))
        }
        try {
            val limit = runBlocking { graph.policy.invalidate(); graph.policy.snapshot(freshUsage = true) }.appLimits.first { it.id == id }
            assertEquals(setOf(target), limit.packages)
        } finally {
            runBlocking { graph.db.appLimitDao().delete(id); graph.policy.invalidate() }
        }
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
        scrollTo("Add apps")
        tap("Add apps")
        check(device.wait(Until.hasObject(By.text("Add apps to this block")), 5_000), "Add apps picker")
        screenshot("11-add-apps-picker")
    }

    @Test fun theFirstRunTestBlockLeavesTheChosenAppsAlone() {
        val chosen = runBlocking {
            val exempt = graph.policy.exempt()
            graph.apps.all().map { it.packageName }.filter { it !in exempt }.take(3)
        }
        runBlocking {
            val last = graph.sessions.lastSetup()
            graph.sessions.saveSelection(chosen)
            val r = graph.sessions.start(StartRequest(BlockSetup(listOf(chosen.first()), SessionType.TIMED, 1), test = true))
            assertTrue(r is StartResult.Started)
            // Neither choosing apps nor the test block becomes "Repeat last block".
            assertEquals(last, graph.sessions.lastSetup())
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
        assertNotNull(device.findObject(By.text("Timed")))
        assertNull("Until I stop is the Quick block card, not a duration", device.findObject(By.text("Until I stop")))
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

    /** Waits for [text] and taps it; fails with the screen contents when it never appears. */
    private fun tap(text: String) {
        val o = device.wait(Until.findObject(By.text(text)), 5_000)
        check(o != null, "Tap target \"$text\"")
        o.click()
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
            shell("mkdir -p $SHOTS")
            shell("screencap -p $SHOTS/$name.png")
        }
    }
}
