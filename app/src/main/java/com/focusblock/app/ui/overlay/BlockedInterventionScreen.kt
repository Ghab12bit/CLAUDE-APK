package com.focusblock.app.ui.overlay

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Divider as HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import androidx.hilt.navigation.compose.hiltViewModel
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.database.entity.BlockedByType
import com.focusblock.app.database.entity.FocusCycleOverride
import com.focusblock.app.service.FocusBlockAccessibilityService
import com.focusblock.app.ui.theme.BackgroundDark
import com.focusblock.app.ui.theme.CardDark
import com.focusblock.app.ui.theme.Divider
import com.focusblock.app.ui.theme.Primary
import com.focusblock.app.ui.theme.TextPrimary
import com.focusblock.app.ui.theme.TextSecondary
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.viewmodel.HomeViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Calm, contextual intervention shown by the real accessibility blocking path.
 * Friction increases only after repeated attempts and every supported escape is
 * recorded through the existing override mechanisms.
 */
@Composable
fun BlockedInterventionScreen(
    packageName: String,
    appName: String,
    blockedBy: BlockedByType,
    onClose: () -> Unit,
    homeViewModel: HomeViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val database = remember { FocusBlockDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    val appIcon = remember(packageName) { AppUtils.getAppIcon(context, packageName) }
    val bitmap = remember(appIcon) { appIcon?.runCatching { toBitmap(128, 128).asImageBitmap() }?.getOrNull() }

    var attemptCount by remember { mutableIntStateOf(1) }
    var focusIntention by remember { mutableStateOf("") }
    var blockUntil by remember { mutableLongStateOf(0L) }
    var remaining by remember { mutableLongStateOf(0L) }
    var pauseSeconds by remember { mutableIntStateOf(0) }
    var showEmergency by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var emergencyError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(packageName, blockedBy) {
        val blockedApp = database.blockedAppDao().getBlockedApp(packageName)
        attemptCount = (blockedApp?.blockedCount ?: 1).coerceAtLeast(1)
        val session = database.quickBlockSessionDao().getActiveSessionSync()
        blockUntil = session?.endTime ?: 0L
        remaining = (blockUntil - System.currentTimeMillis()).coerceAtLeast(0L)
        focusIntention = database.settingsDao().getValue(AppSettings.KEY_LAST_FOCUS_INTENTION).orEmpty()
        pauseSeconds = when {
            attemptCount >= 4 -> 20
            attemptCount >= 2 -> 10
            else -> 0
        }
        while (pauseSeconds > 0) {
            delay(1_000)
            pauseSeconds--
        }
    }
    LaunchedEffect(blockUntil) {
        while (blockUntil > 0L) {
            remaining = (blockUntil - System.currentTimeMillis()).coerceAtLeast(0L)
            delay(1_000)
        }
    }

    Box(Modifier.fillMaxSize().background(BackgroundDark).padding(horizontal = 26.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier.size(74.dp).clip(RoundedCornerShape(21.dp)).background(CardDark)
                    .border(1.dp, Divider, RoundedCornerShape(21.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (bitmap != null) {
                    Image(bitmap, appName, Modifier.size(66.dp).clip(RoundedCornerShape(18.dp)))
                } else {
                    Text(appName.take(1), color = TextSecondary, fontSize = 28.sp)
                }
            }
            Spacer(Modifier.height(24.dp))
            Text(
                when (attemptCount) {
                    1 -> "A pause before opening"
                    2 -> "Second attempt during this focus"
                    else -> "Attempt $attemptCount during this focus"
                }.uppercase(),
                color = TextSecondary,
                fontSize = 11.sp,
                letterSpacing = 1.2.sp
            )
            Text(
                if (blockUntil > 0L) "$appName is blocked\nuntil ${formatTime(blockUntil)}"
                else "$appName is blocked\nby ${protectionName(blockedBy)}",
                color = TextPrimary,
                fontSize = 30.sp,
                lineHeight = 34.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 9.dp)
            )
            if (focusIntention.isNotBlank()) {
                Text(
                    "You chose to work on: $focusIntention",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 14.dp)
                )
            }
            HorizontalDivider(color = Divider, modifier = Modifier.padding(top = 26.dp))
            Text(
                buildString {
                    if (remaining > 0L) append("${formatMinutes(remaining)} remaining")
                    else append(protectionName(blockedBy))
                    if (pauseSeconds > 0) append(" · ${pauseSeconds}s pause")
                },
                color = TextPrimary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(vertical = 15.dp)
            )
            HorizontalDivider(color = Divider)
            Button(
                onClick = onClose,
                enabled = pauseSeconds == 0,
                modifier = Modifier.fillMaxWidth().height(72.dp).padding(top = 18.dp),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary, contentColor = Color.White)
            ) {
                Text(if (pauseSeconds > 0) "Pause for ${pauseSeconds}s" else focusIntention.takeIf { it.isNotBlank() }?.let { "Return to $it" } ?: "Return to what matters")
            }
            OutlinedButton(
                onClick = onClose,
                enabled = pauseSeconds == 0,
                modifier = Modifier.fillMaxWidth().height(58.dp).padding(top = 8.dp),
                shape = RoundedCornerShape(14.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, Divider),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = TextPrimary)
            ) { Text("Open an allowed app") }
            TextButton(onClick = { showEmergency = true }, modifier = Modifier.padding(top = 8.dp)) {
                Text("Emergency access · reason required", color = TextSecondary, fontSize = 12.sp)
            }
            Text(
                "This attempt is recorded in Patterns so FocusBlock can improve future protection.",
                color = TextSecondary,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 12.dp)
            )
        }
    }

    if (showEmergency) {
        AlertDialog(
            onDismissRequest = { showEmergency = false },
            containerColor = CardDark,
            titleContentColor = TextPrimary,
            textContentColor = TextSecondary,
            title = { Text("Emergency access") },
            text = {
                Column {
                    Text("State why access is necessary. FocusBlock keeps the reason with this decision; it does not erase the blocked attempt.")
                    TextField(
                        value = reason,
                        onValueChange = { reason = it; emergencyError = null },
                        placeholder = { Text("Reason") },
                        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = BackgroundDark,
                            unfocusedContainerColor = BackgroundDark,
                            focusedTextColor = TextPrimary,
                            unfocusedTextColor = TextPrimary,
                            focusedIndicatorColor = Primary,
                            unfocusedIndicatorColor = Divider
                        )
                    )
                    emergencyError?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp)) }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (reason.trim().length < 4) {
                        emergencyError = "Please enter a specific reason."
                    } else {
                        scope.launch {
                            val granted = grantEmergencyAccess(blockedBy, packageName, appName, database, homeViewModel, context)
                            if (granted) {
                                database.settingsDao().insert(AppSettings("last_emergency_reason", reason.trim()))
                                showEmergency = false
                                (context as? ComponentActivity)?.finish()
                            } else {
                                emergencyError = "Emergency access is unavailable for this protection. Change it from FocusBlock settings."
                            }
                        }
                    }
                }) { Text("Request access", color = Primary) }
            },
            dismissButton = { TextButton(onClick = { showEmergency = false }) { Text("Cancel", color = TextSecondary) } }
        )
    }
}

private suspend fun grantEmergencyAccess(
    blockedBy: BlockedByType,
    packageName: String,
    appName: String,
    database: FocusBlockDatabase,
    homeViewModel: HomeViewModel,
    context: android.content.Context
): Boolean {
    val now = System.currentTimeMillis()
    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(now))
    return when (blockedBy) {
        BlockedByType.STRICT_MODE -> homeViewModel.emergencyUnlock()
        BlockedByType.APP_TIMER -> {
            val usage = database.appTimerDailyUsageDao().getUsageForDateSync(today)
            if (usage?.dailyOverrideUsed == true) false else {
                database.appTimerDailyUsageDao().activateOverride(today, now + 15 * 60_000L)
                true
            }
        }
        BlockedByType.GLOBAL_LIMIT -> {
            context.sendBroadcast(Intent(FocusBlockAccessibilityService.ACTION_ACTIVATE_EMERGENCY_UNLOCK).setPackage(context.packageName))
            true
        }
        BlockedByType.FOCUS_CYCLE -> {
            val cycle = database.focusCycleDao().getActiveFocusCycleSync() ?: return false
            database.focusCycleOverrideDao().insert(FocusCycleOverride(focusCycleId = cycle.id, packageName = packageName, appName = appName))
            true
        }
        BlockedByType.QUICK_BLOCK,
        BlockedByType.SCHEDULE,
        BlockedByType.HARD_MODE,
        BlockedByType.BEDTIME -> false
    }
}

private fun formatTime(timestamp: Long): String = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))

private fun formatMinutes(milliseconds: Long): String {
    val totalMinutes = (milliseconds / 60_000L).coerceAtLeast(1L)
    return if (totalMinutes >= 60) "${totalMinutes / 60}h ${totalMinutes % 60}m" else "${totalMinutes} minutes"
}

private fun protectionName(type: BlockedByType): String = when (type) {
    BlockedByType.QUICK_BLOCK -> "your focus session"
    BlockedByType.SCHEDULE -> "a scheduled guardrail"
    BlockedByType.STRICT_MODE -> "Strict Lock"
    BlockedByType.HARD_MODE -> "Hard Mode"
    BlockedByType.FOCUS_CYCLE -> "a focus cycle break"
    BlockedByType.APP_TIMER -> "your app time boundary"
    BlockedByType.GLOBAL_LIMIT -> "your daily screen-time boundary"
    BlockedByType.BEDTIME -> "Wind down"
}
