package com.focusblock.app.core

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.ui.intervention.PauseActivity
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The pause screen: after N continuous minutes in one non-essential app, a full-screen "Close app"
 * interrupt (Settings › Notifications › Usage reminders). Research on the one sec app found the
 * option to close the app is what cuts opens; a swipe-away notification is not.
 *
 * Both hosts feed it: the Usage Access service every second, the Accessibility service on each app
 * change and every 30 s. "Continuous" survives a short detour (a reply in WhatsApp, a notification
 * shade): only [AWAY_RESET_MS] away from the app resets its clock. Screen off counts as away.
 *
 * Fallback: when the block screen cannot be started from the background (no "Display over other
 * apps" before Android 10 is not a concern; on 10+ without it), a notification is posted instead.
 */
class PauseTracker(
    private val context: Context,
    private val db: FocusBlockDatabase,
    private val clock: AppClock,
    private val notifier: Notifier,
    private val exempt: suspend () -> Set<String>,
    private val transient: () -> Set<String>,
) {
    private val mutex = Mutex()
    private var pkg: String? = null
    /** When the current stretch in [pkg] began. */
    private var since = 0L
    /** Last moment [pkg] was seen in front (or the screen was on in it). */
    private var lastSeen = 0L
    /** The stretch (pkg + since) the pause was already shown for, so it fires once per stretch. */
    private var shownFor: Pair<String, Long>? = null
    /** Another app seen in front during the grace period, and when it first appeared. */
    private var candidate: Pair<String, Long>? = null
    /** Package → until when "5 more minutes" holds. */
    private val snoozed = HashMap<String, Long>()
    /** Package → when the last "5 more minutes" was granted (one per app per hour). */
    private val lastSnooze = HashMap<String, Long>()

    /** Minutes before the pause, 0 = off. Reads the setting each time: it is cheap and always current. */
    suspend fun thresholdMinutes(): Int {
        if (!notifier.enabled(Notifier.Kind.REMINDERS)) return 0
        return db.settingsDao().getValue(PrefKeys.PAUSE_MINUTES)?.toIntOrNull()?.coerceIn(0, 180) ?: DEFAULT_MINUTES
    }

    /**
     * Report the app in front. [foreground] is null when the screen is off. Call often (every
     * second to every 30 s); the pause fires as soon as the threshold is crossed.
     */
    suspend fun observe(foreground: String?, now: Long = clock.now()) = mutex.withLock {
        val fg = foreground?.takeIf { it !in transient() }
        when {
            // A keyboard or system overlay keeps the clock of the app under it running.
            fg == null && foreground != null -> if (pkg != null) lastSeen = now
            fg != null && fg == pkg -> { lastSeen = now; candidate = null }
            fg != null -> {
                // Another app in front. The tracked app keeps its clock through a short detour;
                // after the grace period the other app takes over, counted from when it appeared.
                if (candidate?.first != fg) candidate = fg to now
                if (pkg == null || now - lastSeen > AWAY_RESET_MS) {
                    pkg = fg; since = candidate!!.second; lastSeen = now; candidate = null
                }
            }
            // Screen off: after the grace period the stretch ends.
            else -> if (pkg != null && now - lastSeen > AWAY_RESET_MS) { pkg = null; since = 0; candidate = null }
        }
        val p = pkg ?: return@withLock
        if (fg != p) return@withLock
        val minutes = thresholdMinutes()
        if (minutes <= 0) return@withLock
        if (p == context.packageName || p in exempt()) return@withLock
        val stretch = now - since
        if (stretch < minutes * 60_000L) return@withLock
        if (shownFor == p to since) return@withLock
        if ((snoozed[p] ?: 0L) > now) return@withLock
        shownFor = p to since
        show(p, (stretch / 60_000L).toInt())
    }

    /** "5 more minutes": allowed once per app per hour, then the pause is close-only. */
    suspend fun snooze(pkg: String): Boolean = mutex.withLock {
        val now = clock.now()
        if (now - (lastSnooze[pkg] ?: 0L) < SNOOZE_COOLDOWN_MS) return@withLock false
        lastSnooze[pkg] = now
        snoozed[pkg] = now + SNOOZE_MS
        // Let the pause fire again at the end of the snooze for this same stretch.
        shownFor = null
        true
    }

    suspend fun canSnooze(pkg: String): Boolean = mutex.withLock { clock.now() - (lastSnooze[pkg] ?: 0L) >= SNOOZE_COOLDOWN_MS }

    /** The user closed the app from the pause screen: its stretch ends now. */
    suspend fun closed(pkg: String) = mutex.withLock { if (this.pkg == pkg) { this.pkg = null; since = 0 } }

    private suspend fun show(pkg: String, minutes: Int) {
        val canShowScreen = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || Settings.canDrawOverlays(context)
        val shown = canShowScreen && runCatching {
            context.startActivity(PauseActivity.intent(context, pkg, minutes, canSnooze = clock.now() - (lastSnooze[pkg] ?: 0L) >= SNOOZE_COOLDOWN_MS))
        }.isSuccess
        if (!shown) notifier.showUsageReminder(pkg, minutes)
    }

    companion object {
        const val DEFAULT_MINUTES = 20
        /** Time away from an app before its continuous-use clock resets. */
        const val AWAY_RESET_MS = 2 * 60_000L
        const val SNOOZE_MS = 5 * 60_000L
        const val SNOOZE_COOLDOWN_MS = 60 * 60_000L
    }
}
