package com.focusblock.app.core

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.Settings
import android.provider.Telephony
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import java.time.ZoneId

/** Clock abstraction so session timing can be tested and survives manual clock changes. */
interface AppClock {
    fun now(): Long
    fun elapsed(): Long
    fun bootCount(): Int
    fun zone(): ZoneId
}

class SystemAppClock(private val context: Context) : AppClock {
    override fun now(): Long = System.currentTimeMillis()
    override fun elapsed(): Long = SystemClock.elapsedRealtime()
    override fun bootCount(): Int = try {
        Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT, -1)
    } catch (e: Exception) {
        -1
    }
    override fun zone(): ZoneId = ZoneId.systemDefault()
}

/**
 * Packages that are never blocked (spec 8.2 level 1 and 9.4): FocusBlock itself, dialler, in-call,
 * telecom, emergency, system UI, Settings (which hosts accessibility settings), home launchers and
 * keyboards. Computed from the device, cached for five minutes.
 */
class SafetyApps(private val context: Context) {
    @Volatile private var cache: Pair<Long, Set<String>>? = null
    @Volatile private var transientCache: Pair<Long, Set<String>>? = null

    fun packages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        cache?.let { if (now - it.first < 5 * 60_000) return it.second }
        val pm = context.packageManager
        val out = HashSet<String>(FIXED)
        out += context.packageName
        runCatching { (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage }.getOrNull()?.let(out::add)
        // Every HOME activity, including fallbacks such as Settings' FallbackHome.
        out += homeActivities().map { it.activityInfo.packageName }
        out += keyboards()
        runCatching {
            pm.queryIntentActivities(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), 0).forEach { out += it.activityInfo.packageName }
        }
        val result = out.toSet()
        cache = now to result
        return result
    }

    /** Packages whose windows appear over other apps (keyboards, system UI): they never change the foreground app. */
    fun transient(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        transientCache?.let { if (now - it.first < 5 * 60_000) return it.second }
        val result = keyboards() + setOf("com.android.systemui", "com.samsung.android.app.cocktailbarservice", "android")
        transientCache = now to result
        return result
    }

    /**
     * Home-screen launchers (not counted in screen time). HOME activities with a negative priority
     * are fallbacks, such as Settings' FallbackHome shown while the phone starts, not launchers.
     */
    fun launchers(): Set<String> = homeActivities().filter { it.priority >= 0 }.map { it.activityInfo.packageName }.toSet()

    private fun homeActivities(): List<ResolveInfo> = runCatching {
        context.packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY,
        )
    }.getOrDefault(emptyList())

    private fun keyboards(): Set<String> = runCatching {
        (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.map { it.packageName }.toSet()
    }.getOrDefault(emptySet())

    fun invalidate() { cache = null; transientCache = null }

    companion object {
        val FIXED = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings",
            "com.samsung.accessibility",
            "com.google.android.marvin.talkback",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.samsung.android.dialer",
            "com.android.incallui",
            "com.samsung.android.incallui",
            "com.samsung.android.app.telephonyui",
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
            "com.samsung.android.emergency",
            "com.android.cellbroadcastreceiver",
            "com.google.android.cellbroadcastreceiver",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
        )
    }
}

/** Installed, user-facing apps (those with a launcher icon). Cached; refreshed on package changes. */
class InstalledApps(private val context: Context) {
    data class App(val packageName: String, val label: String, val isSystem: Boolean)

    @Volatile private var cache: Pair<Long, List<App>>? = null
    private val labels = java.util.concurrent.ConcurrentHashMap<String, String>()

    fun all(): List<App> {
        val now = SystemClock.elapsedRealtime()
        cache?.let { if (now - it.first < 10 * 60_000) return it.second }
        val pm = context.packageManager
        val result = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.applicationInfo }
                .distinctBy { it.packageName }
                .filter { it.packageName != context.packageName }
                .map { info ->
                    val label = info.loadLabel(pm).toString()
                    labels[info.packageName] = label
                    App(info.packageName, label, (info.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0)
                }
                .sortedBy { it.label.lowercase() }
        }.getOrDefault(emptyList())
        cache = now to result
        return result
    }

    fun packages(): Set<String> = all().map { it.packageName }.toSet()

    fun label(pkg: String): String = labels[pkg] ?: runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrNull()?.also { labels[pkg] = it } ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }

    fun isInstalled(pkg: String): Boolean = runCatching { context.packageManager.getApplicationInfo(pkg, 0); true }.getOrDefault(false)

    fun invalidate() { cache = null }
}

/** Default essential apps seeded on first run: phone, messages, clock, camera and maps. */
object DefaultEssentials {
    val CLOCKS = listOf("com.google.android.deskclock", "com.sec.android.app.clockpackage", "com.android.deskclock")
    val MAPS = listOf("com.google.android.apps.maps")

    fun resolve(context: Context): Set<String> {
        val out = HashSet<String>()
        val pm = context.packageManager
        runCatching { (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage }.getOrNull()?.let(out::add)
        runCatching { Telephony.Sms.getDefaultSmsPackage(context) }.getOrNull()?.let(out::add)
        runCatching { pm.resolveActivity(Intent(MediaStore.ACTION_IMAGE_CAPTURE), PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }
            .getOrNull()?.takeIf { it != "android" }?.let(out::add)
        (CLOCKS + MAPS).filter { pkg -> runCatching { pm.getApplicationInfo(pkg, 0); true }.getOrDefault(false) }.forEach(out::add)
        return out
    }
}
