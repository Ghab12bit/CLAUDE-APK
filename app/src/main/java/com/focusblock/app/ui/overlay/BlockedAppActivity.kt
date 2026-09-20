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
import com.focusblock.app.blocking.BlockingEngine
import com.focusblock.app.blocking.StakeTracker
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.BlockedByType
import com.focusblock.app.database.entity.CommitmentLevel
import com.focusblock.app.database.entity.FocusProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
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

        /** Which rule to ask the engine to lift, and how hard it is to stop. */
        const val EXTRA_RULE_ID = "rule_id"
        const val EXTRA_COMMITMENT = "commitment"
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
        val ruleId = intent.getLongExtra(EXTRA_RULE_ID, -1L)
        val commitment = runCatching {
            CommitmentLevel.valueOf(intent.getStringExtra(EXTRA_COMMITMENT).orEmpty())
        }.getOrDefault(CommitmentLevel.OFF)

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
                    ruleId = ruleId,
                    commitment = commitment,
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
/**
 * How long a granted override lasts.
 *
 * Long enough to do the thing that was genuinely needed, short enough that it
 * cannot become the way the evening is spent.
 */
private const val OVERRIDE_MINUTES = 5

/** What came of asking to be let in. */
private sealed interface Grant {
    data class Granted(val minutes: Int) : Grant
    data class Refused(val message: String) : Grant
}

/**
 * Write a bounded override for one rule, honouring its commitment.
 *
 * Goes through BlockingEngine.requestEndRule so the block screen obeys exactly
 * the same rules as the home screen: a LOCKED rule spends the day's emergency
 * unlock, a PIN_LOCKED rule refuses here and says where to go, and an unlocked
 * rule simply opens. The engine already knew how to do all of this; nothing on
 * this screen had ever asked it.
 */
private suspend fun grantOverride(
    context: android.content.Context,
    ruleId: Long,
    minutes: Int
): Grant {
    if (ruleId <= 0L) return Grant.Refused("Couldn't tell which routine is blocking this.")
    return try {
        val db = FocusBlockDatabase.getDatabase(context)
        // A fresh instance is fine: overrides are read live on every
        // evaluation (BlockingEngine reads hasActiveOverride per rule, outside
        // the rule cache), so the running service sees this immediately
        // without sharing the object.
        val engine = BlockingEngine(db.blockRuleDao(), db.blockedAppDao(), db.protectionLockDao())
        when (val result = engine.requestEndRule(ruleId, null)) {
            is BlockingEngine.EndAttempt.Ended ->
                // Lifting one rule does not free the app if another still
                // covers it. Saying so beats bouncing the user straight back
                // into this screen and letting them think the unlock failed.
                if (result.stillBlocked.isEmpty()) {
                    Grant.Granted(minutes)
                } else {
                    Grant.Refused(
                        "That one's lifted, but ${result.stillBlocked.joinToString(" and ")} " +
                            "still covers this app."
                    )
                }
            is BlockingEngine.EndAttempt.Refused -> Grant.Refused(result.message)
            is BlockingEngine.EndAttempt.NeedsPin -> Grant.Refused(
                "This routine is PIN locked. Open FocusBlock to unlock it — " +
                    "there's a wait before the PIN is accepted."
            )
        }
    } catch (e: Exception) {
        Grant.Refused("Couldn't unlock that just now.")
    }
}

/** Send the user back to the app they were reaching for. */
private fun reopen(context: android.content.Context, packageName: String) {
    if (packageName.isBlank()) return
    try {
        context.packageManager.getLaunchIntentForPackage(packageName)?.let {
            it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(it)
        }
    } catch (e: Exception) {
        // Falling through just closes the block screen, which is survivable.
    }
}

@Composable
fun BlockedAppScreen(
    packageName: String,
    appName: String,
    blockedBy: BlockedByType,
    ruleName: String = "",
    endsAt: Long = 0L,
    alsoBlocking: String = "",
    ruleId: Long = -1L,
    commitment: CommitmentLevel = CommitmentLevel.OFF,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val db = remember { FocusBlockDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()

    var profile by remember { mutableStateOf<FocusProfile?>(null) }
    var secondsLeft by remember { mutableStateOf(-1) }
    var recorded by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    var refusal by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val p = withContext(Dispatchers.IO) { db.focusProfileDao().require() }
        profile = p
        secondsLeft = p.pauseSeconds
        // The pause. Long enough for an impulse to crest and fall, short
        // enough not to read as punishment.
        while (secondsLeft > 0) {
            delay(1000)
            secondsLeft -= 1
        }
    }

    // Backing out during or after the pause is the win condition, and the only
    // number in the app that reflects something the user did rather than
    // something the software did.
    fun leave(followed: Boolean) {
        if (!recorded) {
            recorded = true
            scope.launch(Dispatchers.IO) {
                // Through the tracker, not the DAO: writing the counter alone
                // skipped the milestone check, so no milestone could ever be
                // raised however many urges were passed.
                val tracker = StakeTracker(db.focusProfileDao())
                if (followed) tracker.impulseFollowed() else tracker.impulsePassed()
            }
        }
        onClose()
    }

    /**
     * Actually let the user in.
     *
     * This is the repair of the screen's worst behaviour. "I still need to open
     * it" used to record the user as having given in and then simply close --
     * no override was written, so reopening the app was blocked again at once.
     * The button did not do the one thing its label promised, and it charged
     * them for pressing it.
     *
     * It now writes a real, bounded override against this one rule, exactly as
     * the home screen's "end" does, and reports honestly when the rule's
     * commitment will not allow it.
     */
    fun letMeIn(minutes: Int) {
        if (working) return
        working = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                val tracker = StakeTracker(db.focusProfileDao())
                tracker.impulseFollowed()
                grantOverride(context, ruleId, minutes)
            }
            working = false
            when (outcome) {
                is Grant.Granted -> {
                    recorded = true
                    // Straight back to the app they asked for. Sending them to
                    // the launcher after granting entry would make the grant
                    // feel like another refusal.
                    reopen(context, packageName)
                    onClose()
                }
                is Grant.Refused -> refusal = outcome.message
            }
        }
    }

    val p = profile
    val paused = secondsLeft > 0
    val reason = if (ruleName.isNotBlank()) ruleName else "one of your routines"

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
            // The anchor. During the pause it counts down, so the wait is
            // visible and finite rather than an unexplained freeze.
            Box(contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .size(136.dp)
                        .clip(CircleShape)
                        .background(SignalGlow)
                )
                Box(
                    Modifier
                        .size(100.dp)
                        .clip(CircleShape)
                        .background(SignalSoft),
                    contentAlignment = Alignment.Center
                ) {
                    if (paused) {
                        Text(
                            text = "$secondsLeft",
                            color = Signal,
                            fontSize = 38.sp,
                            fontWeight = FontWeight.Bold
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Shield,
                            contentDescription = null,
                            tint = Signal,
                            modifier = Modifier.size(46.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(36.dp))

            if (paused) {
                Text(
                    text = "Hold on",
                    color = TextPrimary,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(12.dp))
                // The user's own words, at the exact moment the urge is
                // concrete and the goal would otherwise be abstract. Written
                // once at setup, never asked for again.
                Text(
                    text = if (p?.hasReason() == true) p.reason else "This time is yours.",
                    color = Signal,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Medium,
                    lineHeight = 25.sp,
                    textAlign = TextAlign.Center
                )
                if (p?.reasonDetail?.isNotBlank() == true) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = p.reasonDetail,
                        color = TextSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                }
                p?.stakeLine()?.let {
                    Spacer(modifier = Modifier.height(18.dp))
                    Text(it, color = TextSecondary, fontSize = 13.sp, textAlign = TextAlign.Center)
                }
            } else {
                Text(
                    text = "$appName is blocked",
                    color = TextPrimary,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
                Spacer(modifier = Modifier.height(14.dp))
                Text(
                    text = if (endsAt > 0L) {
                        "$reason · until " +
                            java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())
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
            }

            Spacer(modifier = Modifier.height(48.dp))

            Button(
                onClick = { leave(followed = false) },
                shape = RoundedCornerShape(28.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Signal),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
            ) {
                Text(
                    text = if (paused) "Put it down" else "Close",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White
                )
            }

            // The escape hatch only appears once the pause has run. Offering it
            // immediately would defeat the pause; withholding it entirely would
            // provoke the reactance that gets blockers uninstalled.
            //
            // Its label now states what it costs, because the cost differs by
            // rule: an unlocked routine simply opens, a locked one spends the
            // day's single emergency unlock. Charging someone their emergency
            // unlock behind a button that said only "I still need to open it"
            // was the screen's least honest moment.
            if (!paused && p?.allowBreathThrough == true) {
                Spacer(modifier = Modifier.height(14.dp))
                TextButton(onClick = { letMeIn(OVERRIDE_MINUTES) }, enabled = !working) {
                    Text(
                        when {
                            working -> "Opening..."
                            commitment == CommitmentLevel.LOCKED ->
                                "Let me in for $OVERRIDE_MINUTES minutes — uses today's unlock"
                            commitment == CommitmentLevel.PIN_LOCKED ->
                                "This one needs your PIN"
                            else -> "Let me in for $OVERRIDE_MINUTES minutes"
                        },
                        color = TextTertiary,
                        fontSize = 13.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }

            refusal?.let { message ->
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    message,
                    color = AccentOrange,
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    lineHeight = 19.sp
                )
            }
        }
    }
}
