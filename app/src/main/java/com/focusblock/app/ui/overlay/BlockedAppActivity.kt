package com.focusblock.app.ui.overlay

import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.ExperimentalAnimationApi
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

        /**
         * The real reason, from BlockingEngine. The BlockedByType above is a
         * coarse category kept for the block log; these carry what the user
         * actually needs at this moment: which rule, until when, and what else
         * would still be blocking if this one ended.
         */
        const val EXTRA_RULE_NAME = "rule_name"
        const val EXTRA_ENDS_AT = "ends_at"
        const val EXTRA_ALSO_BLOCKING = "also_blocking"
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

        val ruleName = intent.getStringExtra(EXTRA_RULE_NAME).orEmpty()
        val endsAt = intent.getLongExtra(EXTRA_ENDS_AT, 0L)
        val alsoBlocking = intent.getStringExtra(EXTRA_ALSO_BLOCKING).orEmpty()

        Log.i(TAG, "Blocking: $appName ($packageName) by $blockedBy / $ruleName")

        setContent {
            FocusBlockTheme {
                BlockedAppScreen(
                    packageName = packageName,
                    appName = appName,
                    blockedBy = blockedBy,
                    ruleName = ruleName,
                    endsAt = endsAt,
                    alsoBlocking = alsoBlocking,
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
/**
 * The block screen.
 *
 * Modelled directly on the reference app, which does this screen best: fully
 * centred, one large glowing mark as the anchor, a short statement, and a
 * single action -- with far more empty space than feels comfortable. The point
 * is calm. This is not a punishment screen and must never read as one.
 *
 * Where it goes beyond the reference: it names the routine the user created and
 * the time it lifts, and states overlap, so ending one rule never looks like it
 * will free the app while another still covers it.
 */
@Composable
fun BlockedAppScreen(
    packageName: String,
    appName: String,
    blockedBy: BlockedByType,
    ruleName: String = "",
    endsAt: Long = 0L,
    alsoBlocking: String = "",
    onClose: () -> Unit
) {
    val reason = if (ruleName.isNotBlank()) ruleName else when (blockedBy) {
        BlockedByType.QUICK_BLOCK -> "a block you started"
        BlockedByType.SCHEDULE, BlockedByType.STRICT_MODE, BlockedByType.HARD_MODE ->
            "one of your routines"
        BlockedByType.FOCUS_CYCLE -> "your hourly limit"
        BlockedByType.APP_TIMER, BlockedByType.GLOBAL_LIMIT -> "your daily limit"
        BlockedByType.BEDTIME -> "your night routine"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BackgroundDark),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 36.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // The anchor: a large, softly glowing shield.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(132.dp)
                        .clip(CircleShape)
                        .background(SignalGlow)
                )
                Box(
                    Modifier
                        .size(96.dp)
                        .clip(CircleShape)
                        .background(SignalSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Shield,
                        contentDescription = null,
                        tint = Signal,
                        modifier = Modifier.size(44.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = appName,
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "is blocked",
                color = TextPrimary,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = if (endsAt > 0L) {
                    "$reason · until " + java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
                        .format(java.util.Date(endsAt))
                } else reason,
                color = Signal,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center
            )

            if (alsoBlocking.isNotBlank()) {
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    text = "Also blocked by $alsoBlocking",
                    color = TextSecondary,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(56.dp))

            Button(
                onClick = onClose,
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Signal),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    "Close",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }
        }
    }
}
