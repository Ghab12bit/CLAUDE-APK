@file:OptIn(ExperimentalMaterial3Api::class)

package com.focusblock.app.ui.overlay

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import android.content.Intent
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.service.FocusBlockAccessibilityService
import com.focusblock.app.ui.theme.*
import com.focusblock.app.utils.TimeUtils
import kotlinx.coroutines.launch

class HardModeUnlockActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            FocusBlockTheme {
                HardModeUnlockScreen(
                    onUnlocked = { finish() },
                    onCancel = { finish() }
                )
            }
        }
    }
}

@Composable
fun HardModeUnlockScreen(
    onUnlocked: () -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val database = remember { FocusBlockDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()

    var pin by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var storedPin by remember { mutableStateOf<String?>(null) }
    var unlockTime by remember { mutableStateOf<Long?>(null) }
    var canUnlock by remember { mutableStateOf(false) }
    var remainingTime by remember { mutableStateOf(0L) }

    // Load settings
    LaunchedEffect(Unit) {
        storedPin = database.settingsDao().getValue(AppSettings.KEY_HARD_MODE_PIN)
        unlockTime = database.settingsDao().getValue(AppSettings.KEY_HARD_MODE_UNLOCK_TIME)?.toLongOrNull()
    }

    // Update remaining time
    LaunchedEffect(unlockTime) {
        while (true) {
            val time = unlockTime
            if (time != null) {
                val remaining = time - System.currentTimeMillis()
                remainingTime = remaining
                canUnlock = remaining <= 0
            } else {
                canUnlock = true
            }
            kotlinx.coroutines.delay(1000)
        }
    }

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
            // Lock icon
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(AccentOrange.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Filled.Security,
                    contentDescription = null,
                    tint = AccentOrange,
                    modifier = Modifier.size(48.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = "Hard Mode Active",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (!canUnlock) {
                // Show countdown
                Text(
                    text = "Unlock available in:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = TimeUtils.formatTimerWithHours(remainingTime),
                    style = MaterialTheme.typography.displayMedium,
                    color = AccentOrange,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(32.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = CardDark)
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Info,
                            contentDescription = null,
                            tint = TextSecondary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Hard Mode prevents you from disabling blocking until the timer expires. This helps you stay committed to your goals.",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                // Show PIN input
                Text(
                    text = "Enter your PIN to disable Hard Mode",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary
                )

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = pin,
                    onValueChange = {
                        if (it.length <= 6 && it.all { c -> c.isDigit() }) {
                            pin = it
                            error = null
                        }
                    },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    singleLine = true,
                    isError = error != null,
                    supportingText = error?.let { { Text(it, color = AccentRed) } },
                    placeholder = { Text("Enter PIN") },
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = AccentOrange,
                        unfocusedBorderColor = Divider,
                        focusedTextColor = TextPrimary,
                        unfocusedTextColor = TextPrimary,
                        cursorColor = AccentOrange
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (pin == storedPin) {
                            scope.launch {
                                // Disable Hard Mode and Strict Mode
                                database.settingsDao().insert(
                                    AppSettings(AppSettings.KEY_HARD_MODE_ENABLED, "false")
                                )
                                database.settingsDao().insert(
                                    AppSettings(AppSettings.KEY_STRICT_MODE_ENABLED, "false")
                                )

                                // Deactivate all Quick Block sessions
                                database.quickBlockSessionDao().deactivateAll()

                                // Unblock all apps that were blocked by Quick Block
                                val blockedApps = database.blockedAppDao().getBlockedPackageNames()
                                blockedApps.forEach { packageName ->
                                    database.blockedAppDao().setBlocked(packageName, false)
                                }

                                // Stop blocking service
                                AppBlockingService.stop(context)

                                // Notify accessibility service to refresh state
                                val refreshIntent = Intent(FocusBlockAccessibilityService.ACTION_REFRESH_STRICT_MODE_CACHE)
                                refreshIntent.`package` = context.packageName
                                context.sendBroadcast(refreshIntent)

                                onUnlocked()
                            }
                        } else {
                            error = "Incorrect PIN"
                            pin = ""
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = AccentOrange),
                    enabled = pin.length >= 4
                ) {
                    Text(
                        text = "Disable Hard Mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            TextButton(onClick = onCancel) {
                Text(
                    text = "Cancel",
                    color = TextSecondary
                )
            }
        }
    }
}
