package com.focusblock.app.ui.overlay

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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.BlockedByType
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.AppUtils
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope

class BlockedAppActivity : ComponentActivity() {

    companion object {
        private const val TAG = "BlockedAppActivity"
        const val EXTRA_PACKAGE_NAME = "package_name"
        const val EXTRA_APP_NAME = "app_name"
        const val EXTRA_BLOCKED_BY = "blocked_by"
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

        val packageName = intent.getStringExtra(EXTRA_PACKAGE_NAME) ?: ""
        val appName = intent.getStringExtra(EXTRA_APP_NAME) ?: "App"
        val blockedByName = intent.getStringExtra(EXTRA_BLOCKED_BY) ?: BlockedByType.QUICK_BLOCK.name
        val blockedBy = try {
            BlockedByType.valueOf(blockedByName)
        } catch (e: Exception) {
            BlockedByType.QUICK_BLOCK
        }

        Log.i(TAG, "Blocking: $appName ($packageName) by $blockedBy")

        setContent {
            FocusBlockTheme {
                BlockedAppScreen(
                    packageName = packageName,
                    appName = appName,
                    blockedBy = blockedBy,
                    onClose = {
                        Log.d(TAG, "Close button pressed, going to home")
                        AppUtils.goToHome(this)
                        finish()
                    }
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume() called")
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause() called")
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy() called")
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        Log.d(TAG, "onBackPressed() - going to home")
        AppUtils.goToHome(this)
        super.onBackPressed()
    }
}

@OptIn(ExperimentalAnimationApi::class)
@Composable
fun BlockedAppScreen(
    packageName: String,
    appName: String,
    blockedBy: BlockedByType,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val appIcon = remember(packageName) { AppUtils.getAppIcon(context, packageName) }
    val database = remember { FocusBlockDatabase.getDatabase(context) }
    val coroutineScope = rememberCoroutineScope()

    var todayCount by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var showFocusCycleOverride by remember { mutableStateOf(false) }
    var focusCycleRemainingBreak by remember { mutableStateOf(0L) }
    var canContinueAnyway by remember { mutableStateOf(false) }

    // Load block counts and Focus Cycle state
    LaunchedEffect(packageName) {
        val app = database.blockedAppDao().getBlockedApp(packageName)
        todayCount = app?.blockedCount ?: 0
        totalCount = app?.totalBlockedCount ?: 0

        // For Focus Cycle, show override option automatically
        if (blockedBy == BlockedByType.FOCUS_CYCLE) {
            val activeCycle = database.focusCycleDao().getActiveFocusCycleSync()
            if (activeCycle != null) {
                val now = System.currentTimeMillis()
                val breakStart = activeCycle.breakStartTime
                if (breakStart != null) {
                    val breakEnd = breakStart + (activeCycle.breakDurationMinutes * 60 * 1000L)
                    focusCycleRemainingBreak = maxOf(0, breakEnd - now)
                }
            }
            showFocusCycleOverride = true
        }
    }

    // Enable "Continue Anyway" after 2.5 second delay for Focus Cycle
    LaunchedEffect(showFocusCycleOverride) {
        if (showFocusCycleOverride) {
            kotlinx.coroutines.delay(2500)
            canContinueAnyway = true
        }
    }

    // Animations
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )

    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF0D1117),
                        Color(0xFF161B22),
                        Color(0xFF0D1117)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Logo and app icon with glow
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.scale(pulseScale)
            ) {
                // Glow effect
                Box(
                    modifier = Modifier
                        .size(160.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    AccentRed.copy(alpha = glowAlpha),
                                    Color.Transparent
                                )
                            )
                        )
                )

                // Main circle
                Box(
                    modifier = Modifier
                        .size(120.dp)
                        .clip(CircleShape)
                        .background(CardDark)
                        .border(3.dp, AccentRed.copy(alpha = 0.5f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    // FocusBlock icon on top of app icon
                    Box(contentAlignment = Alignment.Center) {
                        appIcon?.let { drawable ->
                            Image(
                                bitmap = drawable.toBitmap(80, 80).asImageBitmap(),
                                contentDescription = appName,
                                modifier = Modifier
                                    .size(60.dp)
                                    .clip(CircleShape)
                            )
                        } ?: Icon(
                            imageVector = Icons.Filled.Android,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(60.dp)
                        )

                        // FocusBlock overlay badge
                        Box(
                            modifier = Modifier
                                .size(80.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.5f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Shield,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // App name is blocked
            Text(
                text = appName,
                style = MaterialTheme.typography.headlineSmall,
                color = AccentRed,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "is blocked",
                style = MaterialTheme.typography.titleLarge,
                color = TextPrimary
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Blocked by
            Text(
                text = when (blockedBy) {
                    BlockedByType.QUICK_BLOCK -> "by Quick Block"
                    BlockedByType.SCHEDULE -> "by Schedule"
                    BlockedByType.STRICT_MODE -> "by Strict Mode"
                    BlockedByType.HARD_MODE -> "by Hard Mode"
                    BlockedByType.FOCUS_CYCLE -> "by Focus Cycle (Break Time)"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Block counts
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = CardDark)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "You tried to open it",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextSecondary
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${todayCount}×",
                                style = MaterialTheme.typography.headlineMedium,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "today",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }

                        Box(
                            modifier = Modifier
                                .width(1.dp)
                                .height(48.dp)
                                .background(Divider)
                        )

                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "${totalCount}×",
                                style = MaterialTheme.typography.headlineMedium,
                                color = AccentPurple,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "total",
                                style = MaterialTheme.typography.bodySmall,
                                color = TextSecondary
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Motivational message
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Primary.copy(alpha = 0.1f))
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Lightbulb,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = getMotivationalMessage(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }
            }

            Spacer(modifier = Modifier.height(48.dp))

            // Focus Cycle override section
            if (blockedBy == BlockedByType.FOCUS_CYCLE && showFocusCycleOverride) {
                // Soft-nudge info
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF9C27B0).copy(alpha = 0.1f))
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.SelfImprovement,
                                contentDescription = null,
                                tint = Color(0xFF9C27B0),
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Break Time",
                                style = MaterialTheme.typography.titleMedium,
                                color = Color(0xFF9C27B0),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${(focusCycleRemainingBreak / 60000).toInt()} min remaining",
                            style = MaterialTheme.typography.bodyMedium,
                            color = TextSecondary
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "A short break now means better focus later.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Take Break button (primary)
                Button(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentGreen)
                ) {
                    Icon(Icons.Filled.SelfImprovement, null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Take a Break",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Continue Anyway button (secondary, with delay)
                TextButton(
                    onClick = {
                        if (canContinueAnyway) {
                            // Record the override
                            coroutineScope.launch {
                                val activeCycle = database.focusCycleDao().getActiveFocusCycleSync()
                                if (activeCycle != null) {
                                    database.focusCycleOverrideDao().insert(
                                        com.focusblock.app.database.entity.FocusCycleOverride(
                                            focusCycleId = activeCycle.id,
                                            packageName = packageName,
                                            appName = appName
                                        )
                                    )
                                }
                            }
                            // Go back (allow the app)
                            (context as? ComponentActivity)?.finish()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = canContinueAnyway
                ) {
                    Text(
                        text = if (canContinueAnyway) "Continue Anyway" else "Wait...",
                        color = if (canContinueAnyway) TextSecondary else TextSecondary.copy(alpha = 0.4f),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                // Regular close button for other block types
                Button(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary
                    )
                ) {
                    Text(
                        text = "Close",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun getMotivationalMessage(): String {
    val messages = listOf(
        "Stay focused! You're doing great.",
        "Your future self will thank you.",
        "Every distraction avoided is a win.",
        "Focus is your superpower.",
        "You're stronger than your urges.",
        "Deep work leads to deep results.",
        "Protect your attention.",
        "Small wins build big victories.",
        "You've got this!"
    )
    return remember { messages.random() }
}
