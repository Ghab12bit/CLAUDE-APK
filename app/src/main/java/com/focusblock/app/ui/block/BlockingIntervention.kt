package com.focusblock.app.ui.block

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.focusblock.app.blocking.*
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.*
import com.focusblock.app.ui.focus.InstalledAppIcon
import com.focusblock.app.ui.focus.formatRemaining
import com.focusblock.app.utils.AppUtils
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockingIntervention(packageName: String, appName: String, blockedBy: BlockedByType, onClose: () -> Unit) = BlockingFirstTheme {
    val context = LocalContext.current
    val db = remember { FocusBlockDatabase.getDatabase(context) }
    val scope = rememberCoroutineScope()
    var session by remember { mutableStateOf<QuickBlockSession?>(null) }
    var metadata by remember { mutableStateOf(BlockSessionMetadata()) }
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    var access by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var reminder by remember { mutableStateOf("") }
    LaunchedEffect(packageName) {
        while (isActive) {
            withContext(Dispatchers.IO) { session = db.quickBlockSessionDao().getActiveSessionSync(); metadata = BlockSessionStore.metadata(db); reminder = db.settingsDao().getValue(BlockSessionStore.REMINDER).orEmpty() }
            now = System.currentTimeMillis(); delay(1000)
        }
    }
    BackHandler { onClose() }
    val owned = session?.let { it.id == metadata.id && packageName in QuickBlockPolicy.packages(it.blockedPackages) && !QuickBlockPolicy.isExpired(it.endTime, now) } == true
    val remaining = (session?.endTime?.minus(now) ?: 0).coerceAtLeast(0)
    Surface(color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(26.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Gap(48); InstalledAppIcon(AppUtils.getAppIcon(context, packageName), appName, 66.dp)
            Gap(24); Heading("$appName is paused.")
            Muted(if (owned && blockedBy == BlockedByType.QUICK_BLOCK) "You chose to block this app for this session." else "This app is blocked by ${blockedBy.name.lowercase().replace('_', ' ')}.")
            Gap(26); Line(); Gap()
            Text(if (owned && blockedBy == BlockedByType.QUICK_BLOCK) {
                if (session?.endTime == null) "Until you stop this block" else "${formatRemaining(remaining)} remaining"
            } else "Other active protection still applies")
            if (owned && metadata.strict) Muted("Strict Lock is on · session cannot be stopped")
            Gap(); Line(); Gap(28)
            Action("Back to home screen", onClick = onClose)
            TextButton(onClick = { access = true }) { Text("Need access?") }
            Gap(); Muted("No explanation required to leave. Calls and your essential apps are available.")
            if (reminder.isNotBlank()) { Gap(); Muted(when (reminder) { "stretch" -> "Optional: stand and stretch. Leave the phone here."; "read" -> "Optional: read a page of a book nearby."; else -> "Optional: put your phone out of reach." }) }
        }
    }
    if (access) ModalBottomSheet(onDismissRequest = { access = false; confirm = false }) {
        Column(Modifier.fillMaxWidth().padding(22.dp).navigationBarsPadding()) {
            Heading(if (confirm) "Allow up to 2 minutes?" else "Need access?")
            Muted("Calls and essentials never use this allowance. You can leave now and use them.")
            Gap(); Action("Go to home screen", onClick = onClose)
            Gap(); Muted("This exception only applies to this session. It cannot be renewed. Other limits and schedules may still block the app.")
            Gap()
            val available = owned && metadata.used < metadata.budget && blockedBy == BlockedByType.QUICK_BLOCK
            Action(if (!available) "No session access remaining" else if (confirm) "Use my one-time access" else "Temporary access · up to 2 min", available && !busy) {
                if (!confirm) confirm = true else {
                    busy = true
                    scope.launch {
                        try {
                            val granted = withContext(Dispatchers.IO) { BlockSessionStore.grantOnce(db, packageName) }
                            if (granted) {
                                val launch = context.packageManager.getLaunchIntentForPackage(packageName)
                                if (launch != null) { context.startActivity(launch); (context as? android.app.Activity)?.finish() }
                                else error = "This app no longer has a launchable activity. The allowance remains used."
                            } else error = "The session changed or its allowance was already used."
                        } catch (e: Exception) { error = "Could not grant access. Blocking remains in place." }
                        finally { busy = false }
                    }
                }
            }
            error?.let { Gap(); Text(it, color = MaterialTheme.colorScheme.error) }
            Gap()
        }
    }
}
