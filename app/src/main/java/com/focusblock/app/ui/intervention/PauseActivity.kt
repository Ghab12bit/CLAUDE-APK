package com.focusblock.app.ui.intervention

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.focusblock.app.R
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.Fmt
import com.focusblock.app.ui.components.AppIcon
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.TextLink
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbTheme
import com.focusblock.app.ui.theme.FbType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The pause screen: shown after N continuous minutes in one app (see PauseTracker). Not a block:
 * nothing is forbidden. One big "Close app" (goes home) and, once an hour, a small "5 more minutes".
 */
class PauseActivity : ComponentActivity() {
    private var pkg: String = ""
    /** Set by Close and 5 more minutes; any other way off this screen leaves the stretch unresolved. */
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        if (!bind(intent)) return
    }

    /** singleTask: a second pause while this one is still around arrives here with new extras. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handled = false
        bind(intent)
    }

    private fun bind(intent: Intent?): Boolean {
        pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return false }
        val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
        val canSnooze = intent.getBooleanExtra(EXTRA_CAN_SNOOZE, true)
        val graph = AppGraph.get(applicationContext)
        val p = pkg
        setContent {
            FbTheme {
                // Back behaves like Close: the point of the screen is the deliberate choice.
                BackHandler { closeApp(graph, p) }
                PauseScreen(p, graph.apps.label(p), minutes, canSnooze, onClose = { closeApp(graph, p) }, onMore = { more(graph, p) })
            }
        }
        return true
    }

    override fun onStop() {
        super.onStop()
        // Home or Recents: not a decision. The pause comes back when the app is in front again.
        if (!handled && pkg.isNotEmpty()) {
            val graph = AppGraph.get(applicationContext)
            graph.scope.launch { graph.pauses.dismissed(pkg) }
            finish()
        }
    }

    private fun closeApp(graph: AppGraph, pkg: String) {
        handled = true
        graph.scope.launch { graph.pauses.closed(pkg) }
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun more(graph: AppGraph, pkg: String) {
        handled = true
        graph.scope.launch(Dispatchers.IO) { graph.pauses.snooze(pkg) }
        // The app is still underneath this screen; finishing returns to it.
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_MINUTES = "minutes"
        const val EXTRA_CAN_SNOOZE = "can_snooze"

        fun intent(context: Context, pkg: String, minutes: Int, canSnooze: Boolean): Intent =
            Intent(context, PauseActivity::class.java)
                .putExtra(EXTRA_PACKAGE, pkg)
                .putExtra(EXTRA_MINUTES, minutes)
                .putExtra(EXTRA_CAN_SNOOZE, canSnooze)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}

@Composable
private fun PauseScreen(pkg: String, appName: String, minutes: Int, canSnooze: Boolean, onClose: () -> Unit, onMore: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).heightIn(min = maxHeight).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppIcon(pkg, null, 72.dp)
            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.pause_title, Fmt.minutes(context, minutes), appName),
                style = FbType.title, textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Fb.gutter).semantics { heading() },
            )
            Spacer(Modifier.height(10.dp))
            Text(
                stringResource(R.string.pause_body),
                style = FbType.body.copy(color = Fb.textSecondary), textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = Fb.gutter),
            )
            Spacer(Modifier.height(28.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = Fb.gutter), horizontalAlignment = Alignment.CenterHorizontally) {
                PrimaryButton(stringResource(R.string.pause_close, appName), onClose)
                Spacer(Modifier.height(12.dp))
                if (canSnooze) TextLink(stringResource(R.string.pause_more), onMore, accent = false)
                else Text(stringResource(R.string.pause_no_more), style = FbType.caption.copy(color = Fb.textSecondary), textAlign = TextAlign.Center)
            }
        }
    }
}
