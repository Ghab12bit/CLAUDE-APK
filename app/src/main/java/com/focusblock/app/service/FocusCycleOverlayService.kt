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
import android.widget.FrameLayout
import android.widget.TextView
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.utils.TimeUtils
import kotlinx.coroutines.*
import kotlin.math.abs

/**
 * Floating overlay service that shows Focus Cycle timer
 * Supports expanded (full timer) and collapsed (edge icon) modes
 */
class FocusCycleOverlayService : Service() {

    companion object {
        private const val TAG = "FocusCycleOverlay"
        private const val UPDATE_INTERVAL_MS = 1000L
        private const val CLICK_THRESHOLD = 10 // pixels - movement under this is a click
        private const val EDGE_MARGIN = 0 // Snap to edge
        private const val COLLAPSED_ALPHA = 0.6f
        private const val EXPANDED_ALPHA = 0.95f

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

    // ========== COLLAPSE/EXPAND STATE ==========
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

    override fun onBind(intent: Intent?): IBinder? = null

    @SuppressLint("InflateParams")
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "FocusCycleOverlayService created")

        if (!Settings.canDrawOverlays(this)) {
            Log.w(TAG, "No overlay permission")
            stopSelf()
            return
        }

        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        // Get screen dimensions
        val displayMetrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager?.defaultDisplay?.getMetrics(displayMetrics)
        screenWidth = displayMetrics.widthPixels

        // Create the overlay view with both expanded and collapsed layouts
        overlayView = LayoutInflater.from(this).inflate(R.layout.focus_cycle_overlay, null)

        // Get view references
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
            startUpdating()

            // Start in expanded mode
            setExpandedMode()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            stopSelf()
        }
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

                    // Check if this is a drag or just a tap
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
                        // This was a tap - toggle collapsed/expanded
                        toggleCollapsedMode()
                    } else {
                        // Was dragging - snap to nearest edge
                        snapToEdge()
                    }
                    true
                }
                else -> false
            }
        }
    }

    /**
     * Toggle between collapsed and expanded modes
     */
    private fun toggleCollapsedMode() {
        if (isCollapsed) {
            setExpandedMode()
        } else {
            setCollapsedMode()
        }
    }

    /**
     * Set to expanded mode (full timer display)
     */
    private fun setExpandedMode() {
        isCollapsed = false

        expandedContainer?.visibility = View.VISIBLE
        collapsedContainer?.visibility = View.GONE
        overlayView?.alpha = EXPANDED_ALPHA

        // Update arrow direction based on edge
        updateArrowDirection()

        Log.d(TAG, "Overlay expanded")
    }

    /**
     * Set to collapsed mode (small edge icon)
     */
    private fun setCollapsedMode() {
        isCollapsed = true

        expandedContainer?.visibility = View.GONE
        collapsedContainer?.visibility = View.VISIBLE
        overlayView?.alpha = COLLAPSED_ALPHA

        // Update arrow direction based on edge
        updateArrowDirection()

        // Snap to nearest edge when collapsing
        snapToEdge()

        Log.d(TAG, "Overlay collapsed")
    }

    /**
     * Update arrow direction based on which edge we're on
     */
    private fun updateArrowDirection() {
        collapsedArrow?.text = if (isOnLeftEdge) ">" else "<"
    }

    /**
     * Snap the overlay to the nearest screen edge with animation
     */
    private fun snapToEdge() {
        val currentX = layoutParams.x
        val overlayWidth = overlayView?.width ?: 100

        // Determine which edge is closer
        val distanceToLeft = currentX
        val distanceToRight = screenWidth - currentX - overlayWidth

        val targetX = if (distanceToLeft < distanceToRight) {
            isOnLeftEdge = true
            EDGE_MARGIN
        } else {
            isOnLeftEdge = false
            screenWidth - overlayWidth - EDGE_MARGIN
        }

        // Animate to edge
        animateToPosition(targetX)

        // Update arrow direction
        updateArrowDirection()
    }

    /**
     * Animate overlay to target X position
     */
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

    private fun startUpdating() {
        handler.post(object : Runnable {
            override fun run() {
                serviceScope.launch {
                    updateOverlay()
                }
                handler.postDelayed(this, UPDATE_INTERVAL_MS)
            }
        })
    }

    private suspend fun updateOverlay() {
        val focusCycle = database.focusCycleDao().getActiveFocusCycleSync()

        withContext(Dispatchers.Main) {
            if (focusCycle == null || !focusCycle.isEnabled) {
                stopSelf()
                return@withContext
            }

            val now = System.currentTimeMillis()
            var status = ""
            var timeString = ""

            when {
                focusCycle.isArmed -> {
                    status = "READY"
                    val windowMillis = TimeUtils.focusCycleTimeToMillis(focusCycle.usageWindowMinutes)
                    timeString = formatTime(windowMillis)
                }
                focusCycle.breakStartTime != null -> {
                    status = "BREAK"
                    val breakEnd = focusCycle.breakStartTime +
                            TimeUtils.focusCycleTimeToMillis(focusCycle.breakDurationMinutes)
                    val remaining = maxOf(0, breakEnd - now)
                    timeString = formatTime(remaining)

                    if (remaining <= 0) {
                        stopSelf()
                        return@withContext
                    }
                }
                focusCycle.cycleStartTime != null -> {
                    status = if (focusCycle.isPaused) "PAUSED" else "ACTIVE"
                    val windowMillis = TimeUtils.focusCycleTimeToMillis(focusCycle.usageWindowMinutes)
                    val remaining = maxOf(0, windowMillis - focusCycle.accumulatedUsageMillis)
                    timeString = formatTime(remaining)
                }
            }

            // Update expanded view
            statusText?.text = status
            timerText?.text = timeString

            // Update collapsed view
            collapsedTimer?.text = formatTimeShort(timeString)
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    /**
     * Format time for collapsed view (shorter format)
     */
    private fun formatTimeShort(fullTime: String): String {
        // Remove leading zero from minutes if present
        return if (fullTime.startsWith("0")) {
            fullTime.substring(1)
        } else {
            fullTime
        }
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
