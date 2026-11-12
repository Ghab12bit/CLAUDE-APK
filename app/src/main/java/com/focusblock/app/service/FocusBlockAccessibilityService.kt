package com.focusblock.app.service

import android.accessibilityservice.AccessibilityService
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * OPTIONAL "Faster blocking" add-on.
 *
 * All enforcement lives in [BlockingEngine], which [AppBlockingService] runs using only
 * Usage Access + the overlay permission. This service just feeds the same engine instant
 * window-change events and a native "go home" action, so blocking kicks in a little faster.
 *
 * Many banking and payment apps refuse to run while any third-party Accessibility service
 * is enabled, so this is off by default and the app is fully functional without it.
 *
 * Least privilege: the config only subscribes to window state changes and cannot read
 * window content (see res/xml/accessibility_service_config.xml).
 */
class FocusBlockAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "FocusBlockA11y"

        // Kept here so existing callers keep compiling; the engine owns the behavior.
        const val ACTION_EXTEND_APP_TIMER = BlockingEngine.ACTION_EXTEND_APP_TIMER
        const val ACTION_REFRESH_GLOBAL_LIMIT_CACHE = BlockingEngine.ACTION_REFRESH_GLOBAL_LIMIT_CACHE
        const val ACTION_REFRESH_FOCUS_CYCLE_CACHE = BlockingEngine.ACTION_REFRESH_FOCUS_CYCLE_CACHE
        const val ACTION_REFRESH_STRICT_MODE_CACHE = BlockingEngine.ACTION_REFRESH_STRICT_MODE_CACHE
        const val ACTION_ACTIVATE_EMERGENCY_UNLOCK = BlockingEngine.ACTION_ACTIVATE_EMERGENCY_UNLOCK

        /** True while the optional Accessibility add-on is connected. */
        @Volatile
        var isServiceRunning = false
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        Log.i(TAG, "Faster blocking (Accessibility) connected")
        isServiceRunning = true
        BlockingEngine.goHomeOverride = { performGlobalAction(GLOBAL_ACTION_HOME) }
        BlockingEngine.get(this).start()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val packageName = event.packageName?.toString() ?: return
        BlockingEngine.get(this).onForegroundApp(packageName)
    }

    override fun onInterrupt() {
        // Required override
    }

    override fun onDestroy() {
        Log.i(TAG, "Faster blocking (Accessibility) disconnected")
        isServiceRunning = false
        BlockingEngine.goHomeOverride = null
        // Usage Access host keeps enforcing; only stop the engine if nothing else hosts it.
        if (!AppBlockingService.isRunning) {
            BlockingEngine.get(this).stop()
        }
        super.onDestroy()
    }
}
