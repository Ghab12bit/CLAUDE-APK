package com.focusblock.app.ui.overlay

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.AppUtils
import kotlinx.coroutines.launch

/**
 * Full-screen reflection popup for App Timer
 * Shows when user exceeds their shared time limit on timer apps
 */
class AppTimerReflectionActivity : ComponentActivity() {

    companion object {
        private const val TAG = "AppTimerReflection"
        const val EXTRA_TOTAL_USAGE_MINUTES = "total_usage_minutes"
        const val EXTRA_LIMIT_MINUTES = "limit_minutes"
        const val EXTRA_IS_ESCALATION = "is_escalation"
        const val EXTRA_TIMER_APPS = "timer_apps"

        fun createIntent(
            context: Context,
            totalUsageMinutes: Int,
            limitMinutes: Int,
            isEscalation: Boolean,
            timerApps: String
        ): Intent {
            return Intent(context, AppTimerReflectionActivity::class.java).apply {
                putExtra(EXTRA_TOTAL_USAGE_MINUTES, totalUsageMinutes)
                putExtra(EXTRA_LIMIT_MINUTES, limitMinutes)
                putExtra(EXTRA_IS_ESCALATION, isEscalation)
                putExtra(EXTRA_TIMER_APPS, timerApps)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Log.d(TAG, "onCreate() called")
        super.onCreate(savedInstanceState)

        // Ensure this activity stays on top
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        enableEdgeToEdge()

        val totalUsageMinutes = intent.getIntExtra(EXTRA_TOTAL_USAGE_MINUTES, 0)
        val limitMinutes = intent.getIntExtra(EXTRA_LIMIT_MINUTES, 30)
        val isEscalation = intent.getBooleanExtra(EXTRA_IS_ESCALATION, false)
        val timerApps = intent.getStringExtra(EXTRA_TIMER_APPS) ?: ""

        Log.i(TAG, "Showing reflection: usage=$totalUsageMinutes min, limit=$limitMinutes min, escalation=$isEscalation")

        setContent {
            FocusBlockTheme {
                AppTimerReflectionScreen(
                    totalUsageMinutes = totalUsageMinutes,
                    limitMinutes = limitMinutes,
                    isEscalation = isEscalation,
                    timerApps = timerApps,
                    onAddToQuickBlock = {
                        Log.d(TAG, "User chose to add apps to Quick Block")
                        addAppsToQuickBlock(timerApps)
                        AppUtils.goToHome(this)
                        finish()
                    },
                    onContinue = {
                        Log.d(TAG, "User chose to continue")
                        finish()
                    }
                )
            }
        }
    }

    private fun addAppsToQuickBlock(timerApps: String) {
        val packageNames = timerApps.split(",").filter { it.isNotBlank() }
        if (packageNames.isEmpty()) return

        val database = FocusBlockDatabase.getDatabase(this)
        kotlinx.coroutines.GlobalScope.launch {
            try {
                // Mark as added to quick block in daily usage
                val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                    .format(java.util.Date())
                database.appTimerDailyUsageDao().markAddedToQuickBlock(today)

                // Start a Quick Block session with all timer apps for 60 minutes
                val existingSession = database.quickBlockSessionDao().getActiveSessionSync()
                if (existingSession != null) {
                    // Add to existing session
                    val existingPackages = existingSession.blockedPackages.split(",").filter { it.isNotBlank() }
                    val combinedPackages = (existingPackages + packageNames).distinct().joinToString(",")
                    database.quickBlockSessionDao().update(
                        existingSession.copy(blockedPackages = combinedPackages)
                    )
                } else {
                    // Create new session
                    val endTime = System.currentTimeMillis() + 60 * 60 * 1000L // 1 hour
                    database.quickBlockSessionDao().insert(
                        com.focusblock.app.database.entity.QuickBlockSession(
                            startTime = System.currentTimeMillis(),
                            endTime = endTime,
                            blockedPackages = packageNames.joinToString(","),
                            isActive = true
                        )
                    )
                }
                Log.i(TAG, "Added ${packageNames.size} apps to Quick Block")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to add apps to Quick Block", e)
            }
        }
    }

    override fun onBackPressed() {
        // Don't allow back press, user must make a choice
    }
}

@Composable
fun AppTimerReflectionScreen(
    totalUsageMinutes: Int,
    limitMinutes: Int,
    isEscalation: Boolean,
    timerApps: String,
    onAddToQuickBlock: () -> Unit,
    onContinue: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )

    val gradientColors = if (isEscalation) {
        listOf(
            Color(0xFF1A0A0A),
            Color(0xFF2D0A0A),
            Color(0xFF1A0A0A)
        )
    } else {
        listOf(
            Color(0xFF0A0A1A),
            Color(0xFF0A1A2D),
            Color(0xFF0A0A1A)
        )
    }

    val accentColor = if (isEscalation) Color(0xFFFF6B6B) else Color(0xFF4ECDC4)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(gradientColors))
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Icon
            Box(
                modifier = Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(accentColor.copy(alpha = pulseAlpha)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isEscalation) Icons.Filled.Warning else Icons.Filled.Timer,
                    contentDescription = null,
                    tint = accentColor,
                    modifier = Modifier.size(64.dp)
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Title
            Text(
                text = if (isEscalation) "Extended Screen Time" else "Time Limit Reached",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Usage stats
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark.copy(alpha = 0.8f))
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = formatTime(totalUsageMinutes),
                        style = MaterialTheme.typography.displaySmall,
                        color = accentColor,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "spent on timer apps today",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Progress bar
                    val progress = (totalUsageMinutes.toFloat() / limitMinutes).coerceAtMost(2f)
                    val progressBarValue = (progress / 2f).coerceAtMost(1f)
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(8.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(SurfaceDark)
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(progressBarValue)
                                .fillMaxHeight()
                                .background(accentColor)
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = "Your limit: $limitMinutes min",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Supportive message
            Text(
                text = if (isEscalation) {
                    "You've been on these apps for a while now. Taking a real break could help you feel more refreshed and focused."
                } else {
                    "You've reached your daily screen time goal. This is a good moment to pause and check in with yourself."
                },
                style = MaterialTheme.typography.bodyLarge,
                color = TextSecondary,
                textAlign = TextAlign.Center,
                lineHeight = 24.sp
            )

            Spacer(modifier = Modifier.height(40.dp))

            // Action buttons
            Button(
                onClick = onAddToQuickBlock,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = accentColor)
            ) {
                Icon(
                    imageVector = Icons.Filled.Block,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Block these apps for today",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedButton(
                onClick = onContinue,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                shape = RoundedCornerShape(16.dp),
                border = BorderStroke(1.dp, TextSecondary.copy(alpha = 0.3f))
            ) {
                Text(
                    text = "Continue anyway",
                    style = MaterialTheme.typography.titleMedium,
                    color = TextSecondary
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Gentle reminder
            Text(
                text = "You're doing great by being mindful 💙",
                style = MaterialTheme.typography.labelMedium,
                color = TextSecondary.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
    }
}

private fun formatTime(minutes: Int): String {
    val hours = minutes / 60
    val mins = minutes % 60
    return if (hours > 0) {
        "${hours}h ${mins}m"
    } else {
        "${mins}m"
    }
}
