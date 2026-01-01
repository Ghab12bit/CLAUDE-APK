package com.focusblock.app.service

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.TextView
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.FocusCycle
import com.focusblock.app.utils.TimeUtils
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Floating overlay service that shows Focus Cycle timer
 * Uses TIMESTAMP-BASED timing - no independent counters
 * All elapsed time computed as: now - sessionStartTimestamp
 */
class FocusCycleOverlayService : Service() {

    companion object {
        private const val TAG = "FocusCycleOverlay"
        private const val UPDATE_INTERVAL_MS = 100L // Fast refresh for smooth display
        private const val CLICK_THRESHOLD = 10
        private const val EDGE_MARGIN = 0
        private const val COLLAPSED_ALPHA = 0.6f
        private const val EXPANDED_ALPHA = 0.95f

        const val ACTION_SHOW = "com.focusblock.app.SHOW_OVERLAY"
        const val ACTION_HIDE = "com.focusblock.app.HIDE_OVERLAY"

        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "No overlay permission, cannot start overlay service")
                return
            }
            val intent = Intent(context, FocusCycleOverlayService::class.java)
            context.startService(intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, FocusCycleOverlayService::class.java)
            context.stopService(intent)
        }

        /**
         * Show the overlay (when tracked app is in foreground)
         */
        fun show(context: Context) {
            if (!Settings.canDrawOverlays(context)) return
            val intent = Intent(context, FocusCycleOverlayService::class.java).apply {
                action = ACTION_SHOW
            }
            context.startService(intent)
        }

        /**
         * Hide the overlay (when non-tracked app is in foreground)
         */
        fun hide(context: Context) {
            val intent = Intent(context, FocusCycleOverlayService::class.java).apply {
                action = ACTION_HIDE
            }
            context.startService(intent)
        }

        /**
         * Compute LIVE elapsed usage time from timestamps
         * This is the single source of truth for timing
         */
        fun computeElapsedUsageMillis(cycle: FocusCycle, now: Long = System.currentTimeMillis()): Long {
            // If paused or no active session, return accumulated only
            if (cycle.isPaused || cycle.lastActiveTime == null) {
                return cycle.accumulatedUsageMillis
            }
            // LIVE calculation: accumulated + current session
            val currentSessionMillis = now - cycle.lastActiveTime
            return cycle.accumulatedUsageMillis + currentSessionMillis
        }

        /**
         * Compute remaining time in usage window
         */
        fun computeRemainingMillis(cycle: FocusCycle, now: Long = System.currentTimeMillis()): Long {
            val usageWindowMillis = TimeUtils.focusCycleTimeToMillis(cycle.usageWindowMinutes)
            val elapsed = computeElapsedUsageMillis(cycle, now)
            return maxOf(0L, usageWindowMillis - elapsed)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val database by lazy { FocusBlockDatabase.getDatabase(applicationContext) }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f
    private var hasMoved = false

    // Collapse/expand state
    private var isCollapsed = false
    private var isOnLeftEdge = true
    private var screenWidth = 0
    private lateinit var layoutParams: WindowManager.LayoutParams

    // View references
    private var expandedContainer: View? = null
    private var collapsedContainer: View? = null
    private var timerText: TextView? = null
    private var statusText: TextView? = null
    private var collapsedTimer: TextView? = null
    private var collapsedArrow: TextView? = null

    // Cached cycle for display (updated from DB periodically)
    @Volatile private var cachedCycle: FocusCycle? = null
    private var lastDbFetch: Long = 0
    private val DB_FETCH_INTERVAL = 2000L // Fetch from DB every 2s

    // Visibility state - overlay hidden when user is on non-tracked app
    private var isOverlayVisible = true
    private var isViewAdded = false
    private var lastVisibilityCheck: Long = 0L
    private val VISIBILITY_CHECK_INTERVAL = 5000L // Check every 5 seconds

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_SHOW -> {
                Log.d(TAG, "Received ACTION_SHOW")
                showOverlayView()
            }
            ACTION_HIDE -> {
                Log.d(TAG, "Received ACTION_HIDE")
                hideOverlayView()
            }
        }
        return START_STICKY
    }

    private fun showOverlayView() {
        isOverlayVisible = true
        overlayView?.let { view ->
            handler.post {
                view.visibility = View.VISIBLE
                // Force layout refresh to ensure visibility
                try {
                    windowManager?.updateViewLayout(view, layoutParams)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh layout on show", e)
                }
                Log.d(TAG, "Overlay shown and layout refreshed")
            }
        }
    }

    private fun hideOverlayView() {
        if (!isOverlayVisible) return
        isOverlayVisible = false
        overlayView?.let { view ->
            handler.post {
                view.visibility = View.GONE
                Log.d(TAG, "Overlay hidden")
            }
        }
    }

    @SuppressLint("InflateParams")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FocusCycleOverlayService created - using timestamp-based timing")

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels

        overlayView = LayoutInflater.from(this).inflate(R.layout.focus_cycle_overlay, null)

        expandedContainer = overlayView?.findViewById(R.id.expanded_container)
        collapsedContainer = overlayView?.findViewById(R.id.collapsed_container)
        timerText = overlayView?.findViewById(R.id.timer_text)
        statusText = overlayView?.findViewById(R.id.status_text)
        collapsedTimer = overlayView?.findViewById(R.id.collapsed_timer)
        collapsedArrow = overlayView?.findViewById(R.id.collapsed_arrow)

        layoutParams = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.START
            x = EDGE_MARGIN
            y = 300
        }

        try {
            windowManager?.addView(overlayView, layoutParams)
            setupTouchListener()

            // Initial DB fetch
            fetchCycleFromDb()

            // Start fast UI refresh (100ms for smooth countdown)
            startDisplayRefresh()

            setExpandedMode()

            // Start hidden - will be shown when tracked app is opened
            isOverlayVisible = false
            overlayView?.visibility = View.GONE
            Log.d(TAG, "Overlay created but hidden - waiting for tracked app")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            stopSelf()
        }
    }

    /**
     * Fetch cycle from database (runs less frequently)
     */
    private fun fetchCycleFromDb() {
        serviceScope.launch {
            try {
                val cycle = database.focusCycleDao().getActiveFocusCycleSync()
                cachedCycle = cycle
                lastDbFetch = System.currentTimeMillis()

                if (cycle == null || !cycle.isEnabled) {
                    withContext(Dispatchers.Main) {
                        stopSelf()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch cycle from DB", e)
            }
        }
    }

    /**
     * Start display refresh loop
     * Runs every 100ms but computes time from LIVE timestamps
     */
    private fun startDisplayRefresh() {
        handler.post(object : Runnable {
            override fun run() {
                // Refresh DB periodically
                val now = System.currentTimeMillis()
                if (now - lastDbFetch > DB_FETCH_INTERVAL) {
                    fetchCycleFromDb()
                }

                // Periodic visibility enforcement - ensures overlay stays visible
                // Android can sometimes hide the view, this forces it back
                if (now - lastVisibilityCheck > VISIBILITY_CHECK_INTERVAL) {
                    lastVisibilityCheck = now
                    ensureVisibility()
                }

                // Update display from cached cycle using LIVE timestamps
                updateDisplayFromTimestamps()

                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        })
    }

    /**
     * Ensure the overlay is visible if it should be.
     * This fixes the issue where Android may hide the view after 30-40 seconds.
     */
    private fun ensureVisibility() {
        if (!isOverlayVisible) return // User intentionally hid it

        overlayView?.let { view ->
            if (view.visibility != View.VISIBLE) {
                Log.w(TAG, "Overlay was hidden by system, forcing visible")
                view.visibility = View.VISIBLE

                // Also refresh the layout to ensure it's properly displayed
                try {
                    windowManager?.updateViewLayout(view, layoutParams)
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to refresh layout", e)
                }
            }

            // Also ensure alpha is correct
            if (isCollapsed && view.alpha != COLLAPSED_ALPHA) {
                view.alpha = COLLAPSED_ALPHA
            } else if (!isCollapsed && view.alpha != EXPANDED_ALPHA) {
                view.alpha = EXPANDED_ALPHA
            }
        }
    }

    /**
     * Update display using LIVE timestamp calculations
     * No counters - always computed from timestamps
     */
    private fun updateDisplayFromTimestamps() {
        val cycle = cachedCycle ?: return
        if (!cycle.isEnabled) {
            stopSelf()
            return
        }

        val now = System.currentTimeMillis()
        var status = ""
        var remainingMillis = 0L

        when {
            cycle.isArmed -> {
                status = "READY"
                remainingMillis = TimeUtils.focusCycleTimeToMillis(cycle.usageWindowMinutes)
            }
            cycle.breakStartTime != null -> {
                status = "BREAK"
                val breakEnd = cycle.breakStartTime +
                    TimeUtils.focusCycleTimeToMillis(cycle.breakDurationMinutes)
                remainingMillis = maxOf(0L, breakEnd - now)

                if (remainingMillis <= 0) {
                    stopSelf()
                    return
                }
            }
            cycle.cycleStartTime != null -> {
                status = if (cycle.isPaused) "PAUSED" else "ACTIVE"
                // LIVE timestamp calculation
                remainingMillis = computeRemainingMillis(cycle, now)

                // Log for verification
                if (!cycle.isPaused) {
                    val elapsed = computeElapsedUsageMillis(cycle, now)
                    Log.v(TAG, "LIVE timing: elapsed=${elapsed}ms, remaining=${remainingMillis}ms, " +
                            "accumulated=${cycle.accumulatedUsageMillis}, lastActive=${cycle.lastActiveTime}")
                }
            }
        }

        val timeString = formatTime(remainingMillis)

        // Update views on main thread
        statusText?.text = status
        timerText?.text = timeString
        collapsedTimer?.text = formatTimeShort(timeString)
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener() {
        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val deltaX = event.rawX - initialTouchX
                    val deltaY = event.rawY - initialTouchY

                    if (abs(deltaX) > CLICK_THRESHOLD || abs(deltaY) > CLICK_THRESHOLD) {
                        hasMoved = true
                    }

                    if (hasMoved) {
                        layoutParams.x = initialX + deltaX.toInt()
                        layoutParams.y = initialY + deltaY.toInt()
                        windowManager?.updateViewLayout(overlayView, layoutParams)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) {
                        toggleCollapsedMode()
                    } else {
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun toggleCollapsedMode() {
        if (isCollapsed) setExpandedMode() else setCollapsedMode()
    }

    private fun setExpandedMode() {
        isCollapsed = false
        expandedContainer?.visibility = View.VISIBLE
        collapsedContainer?.visibility = View.GONE
        overlayView?.alpha = EXPANDED_ALPHA
        updateArrowDirection()
        Log.d(TAG, "Overlay expanded")
    }

    private fun setCollapsedMode() {
        isCollapsed = true
        expandedContainer?.visibility = View.GONE
        collapsedContainer?.visibility = View.VISIBLE
        overlayView?.alpha = COLLAPSED_ALPHA
        updateArrowDirection()
        snapToEdge()
        Log.d(TAG, "Overlay collapsed")
    }

    private fun updateArrowDirection() {
        collapsedArrow?.text = if (isOnLeftEdge) ">" else "<"
    }

    private fun snapToEdge() {
        val currentX = layoutParams.x
        val overlayWidth = overlayView?.width ?: 100
        val distanceToLeft = currentX
        val distanceToRight = screenWidth - currentX - overlayWidth

        val targetX = if (distanceToLeft < distanceToRight) {
            isOnLeftEdge = true
            EDGE_MARGIN
        } else {
            isOnLeftEdge = false
            screenWidth - overlayWidth - EDGE_MARGIN
        }

        animateToPosition(targetX)
        updateArrowDirection()
    }

    private fun animateToPosition(targetX: Int) {
        val animator = ValueAnimator.ofInt(layoutParams.x, targetX)
        animator.duration = 200
        animator.interpolator = DecelerateInterpolator()
        animator.addUpdateListener { animation ->
            layoutParams.x = animation.animatedValue as Int
            try {
                windowManager?.updateViewLayout(overlayView, layoutParams)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update layout during animation", e)
            }
        }
        animator.start()
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    private fun formatTimeShort(fullTime: String): String {
        return if (fullTime.startsWith("0")) fullTime.substring(1) else fullTime
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        serviceScope.cancel()
        overlayView?.let {
            try {
                windowManager?.removeView(it)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to remove overlay view", e)
            }
        }
        overlayView = null
        Log.d(TAG, "FocusCycleOverlayService destroyed")
    }
}
