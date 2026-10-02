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
        screenshot("intervention-${System.nanoTime()}")
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
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        check(device.wait(Until.hasObject(By.textStartsWith("Blocking until")), 15_000), "Active block title")
        check(device.hasObject(By.text("“Finish the Q3 report”")), "Intention line")
        // The actions sit below the fold on a phone-sized screen.
        device.findObject(By.scrollable(true).pkg(context.packageName))
            ?.scrollUntil(Direction.DOWN, Until.findObject(By.text("Add 15 minutes")))
        check(device.wait(Until.hasObject(By.text("Add 15 minutes")), 5_000), "Add 15 minutes")
        screenshot("block-active")
        device.findObject(By.text("Rules")).click()
        check(device.wait(Until.hasObject(By.text("What runs automatically")), 10_000), "Rules tab")
        screenshot("rules")
        device.findObject(By.text("Activity")).click()
        check(device.wait(Until.hasObject(By.text("What affected your focus")), 10_000), "Activity tab")
        screenshot("activity")
        device.findObject(By.desc("Settings")).click()
        check(device.wait(Until.hasObject(By.text("Blocking health")), 10_000), "Settings")
        screenshot("settings")
    }

    @Test fun idleBlockTabShowsSetupWithoutGiantNumber() {
        runBlocking { graph.sessions.saveSelection(listOf(target)) }
        context.startActivity(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        check(device.wait(Until.hasObject(By.text("Block distractions")), 15_000), "Idle Block tab")
        assertNotNull(device.findObject(By.text("What are you working on?")))
        assertNotNull(device.findObject(By.text("Intervals")))
        assertNotNull(device.findObject(By.text("Until I stop")))
        assertNull(device.findObject(By.text("Cycles")))
        screenshot("block-idle")
    }

    private fun screenshot(name: String) {
        runCatching {
            device.waitForIdle()
            val bitmap = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).takeScreenshot() ?: return
            val file = File(context.filesDir, "screenshots/$name.png")
            file.parentFile?.mkdirs()
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
