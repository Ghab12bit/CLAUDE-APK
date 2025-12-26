package com.focusblock.app.service

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
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.utils.TimeUtils
import kotlinx.coroutines.*

/**
 * Floating overlay service that shows Focus Cycle timer
 * This is more reliable than notifications on some devices
 */
class FocusCycleOverlayService : Service() {

    companion object {
        private const val TAG = "FocusCycleOverlay"
        private const val UPDATE_INTERVAL_MS = 1000L

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

        // Create a simple overlay view
        overlayView = LayoutInflater.from(this).inflate(R.layout.focus_cycle_overlay, null)

        val layoutParams = WindowManager.LayoutParams().apply {
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
            x = 50
            y = 200
        }

        try {
            windowManager?.addView(overlayView, layoutParams)
            setupTouchListener(layoutParams)
            startUpdating()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay view", e)
            stopSelf()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupTouchListener(params: WindowManager.LayoutParams) {
        overlayView?.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = params.x
                    initialY = params.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = initialX + (event.rawX - initialTouchX).toInt()
                    params.y = initialY + (event.rawY - initialTouchY).toInt()
                    windowManager?.updateViewLayout(overlayView, params)
                    true
                }
                else -> false
            }
        }
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
            val timerText = overlayView?.findViewById<TextView>(R.id.timer_text)
            val statusText = overlayView?.findViewById<TextView>(R.id.status_text)

            if (focusCycle == null || !focusCycle.isEnabled) {
                // No active Focus Cycle - hide or stop
                stopSelf()
                return@withContext
            }

            val now = System.currentTimeMillis()

            when {
                focusCycle.isArmed -> {
                    statusText?.text = "READY"
                    val windowMillis = TimeUtils.focusCycleTimeToMillis(focusCycle.usageWindowMinutes)
                    timerText?.text = formatTime(windowMillis)
                }
                focusCycle.breakStartTime != null -> {
                    statusText?.text = "BREAK"
                    val breakEnd = focusCycle.breakStartTime +
                            TimeUtils.focusCycleTimeToMillis(focusCycle.breakDurationMinutes)
                    val remaining = maxOf(0, breakEnd - now)
                    timerText?.text = formatTime(remaining)

                    // Check if break ended
                    if (remaining <= 0) {
                        stopSelf()
                    }
                }
                focusCycle.cycleStartTime != null -> {
                    statusText?.text = if (focusCycle.isPaused) "PAUSED" else "ACTIVE"
                    val windowMillis = TimeUtils.focusCycleTimeToMillis(focusCycle.usageWindowMinutes)
                    val remaining = maxOf(0, windowMillis - focusCycle.accumulatedUsageMillis)
                    timerText?.text = formatTime(remaining)
                }
            }
        }
    }

    private fun formatTime(millis: Long): String {
        val totalSeconds = (millis / 1000).toInt()
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
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
