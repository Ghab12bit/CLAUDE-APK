package com.focusblock.app.ui.intervention

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.lifecycle.createSavedStateHandle
import com.focusblock.app.R
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.Fmt
import com.focusblock.app.policy.BlockDecision
import com.focusblock.app.policy.BlockReason
import com.focusblock.app.policy.FrictionPolicy
import com.focusblock.app.policy.ReasonType
import com.focusblock.app.policy.SessionInput
import com.focusblock.app.policy.SessionType
import com.focusblock.app.policy.Strength
import com.focusblock.app.ui.components.AppIcon
import com.focusblock.app.ui.components.FbDivider
import com.focusblock.app.ui.components.LabeledField
import com.focusblock.app.ui.components.PrimaryButton
import com.focusblock.app.ui.components.SecondaryButton
import com.focusblock.app.ui.components.TextLink
import com.focusblock.app.ui.theme.Fb
import com.focusblock.app.ui.theme.FbTheme
import com.focusblock.app.ui.theme.FbType

/**
 * The block screen (spec 4.5). Shown only when a blocked app is opened. Calm: no glow, no pulsing,
 * no shield. One component for sessions, routines, bedtime, app limits, the daily limit and Focus
 * Cycles; each brings its own reason and reset time.
 */
class InterventionActivity : ComponentActivity() {
    private val viewModel: InterventionViewModel by viewModels {
        viewModelFactory { initializer { InterventionViewModel(AppGraph.get(applicationContext), createSavedStateHandle()) } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        bindIntent(intent)
        setContent {
            FbTheme {
                val state by viewModel.state.collectAsStateWithLifecycle()
                BackHandler { goHome() }
                InterventionScreen(
                    state = state,
                    onBackHome = ::goHome,
                    onEndBlock = ::openEndBlock,
                    onEmergency = viewModel::openEmergency,
                    onCloseEmergency = viewModel::closeEmergency,
                    onReason = viewModel::setReason,
                    onRequestEmergency = viewModel::requestEmergency,
                    onUseEmergency = viewModel::useEmergency,
                    onCancelEmergency = viewModel::cancelEmergency,
                    onLaunchApp = ::launchBlockedApp,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        bindIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        viewModel.refresh(resetWait = false)
    }

    private fun bindIntent(intent: Intent?) {
        val pkg = intent?.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        viewModel.bind(pkg, intent.getLongExtra(EXTRA_LOG, 0), intent.getIntExtra(EXTRA_ATTEMPT, 1))
    }

    private fun goHome() {
        viewModel.backHome()
        startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    private fun launchBlockedApp() {
        val s = viewModel.state.value
        // Say how long the app stays open (emergency access); a notification also counts down.
        s.openedUntil?.let { until ->
            val left = com.focusblock.app.core.Fmt.duration(this, until - System.currentTimeMillis() + 30_000)
            android.widget.Toast.makeText(this, getString(R.string.unlock_toast, s.appName, left, com.focusblock.app.core.Fmt.time(this, until)), android.widget.Toast.LENGTH_LONG).show()
        }
        packageManager.getLaunchIntentForPackage(s.pkg)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        finish()
    }

    /** "End this block…": opens FocusBlock on the end-early sheet, where its wait and hold apply. */
    private fun openEndBlock() {
        startActivity(
            Intent(this, com.focusblock.app.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(com.focusblock.app.ui.MainActivity.EXTRA_OPEN, com.focusblock.app.ui.MainActivity.OPEN_END_EARLY),
        )
        finish()
    }

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_LOG = "log"
        const val EXTRA_ATTEMPT = "attempt"

        fun intent(context: Context, pkg: String, logId: Long, attempt: Int): Intent =
            Intent(context, InterventionActivity::class.java)
                .putExtra(EXTRA_PACKAGE, pkg)
                .putExtra(EXTRA_LOG, logId)
                .putExtra(EXTRA_ATTEMPT, attempt)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)
    }
}

/** "Blocked by your 45-min block" / "Blocked by Evening routine" (spec 3.2). */
@Composable
fun reasonLine(reason: BlockReason, session: SessionInput?): String = when (reason.type) {
    ReasonType.SESSION -> when (session?.type) {
        SessionType.TIMED -> stringResource(R.string.reason_session_timed, (((session.plannedEndAt ?: session.startedAt) - session.startedAt) / 60_000L).toInt())
        SessionType.INTERVALS -> stringResource(R.string.reason_session_intervals)
        else -> stringResource(R.string.reason_session_open)
    }
    ReasonType.ROUTINE -> stringResource(R.string.reason_rule, reason.name)
    ReasonType.BEDTIME -> stringResource(R.string.reason_bedtime)
    ReasonType.APP_LIMIT -> stringResource(R.string.reason_app_limit, reason.name)
    ReasonType.DAILY_LIMIT -> stringResource(R.string.reason_daily_limit)
    ReasonType.FOCUS_CYCLE -> stringResource(R.string.reason_focus_cycle, reason.name)
}

/** Short name of a reason for "Also blocked by …". */
@Composable
fun reasonName(reason: BlockReason, session: SessionInput?): String = when (reason.type) {
    ReasonType.SESSION -> stringResource(R.string.name_your_block)
    ReasonType.BEDTIME -> stringResource(R.string.name_bedtime)
    ReasonType.DAILY_LIMIT -> stringResource(R.string.name_daily_limit)
    else -> reason.name
}

@Composable
fun InterventionScreen(
    state: InterventionState,
    onBackHome: () -> Unit,
    onEndBlock: () -> Unit,
    onEmergency: () -> Unit,
    onCloseEmergency: () -> Unit,
    onReason: (String) -> Unit,
    onRequestEmergency: () -> Unit,
    onUseEmergency: () -> Unit,
    onCancelEmergency: () -> Unit,
    onLaunchApp: () -> Unit,
) {
    val context = LocalContext.current
    val decision = state.decision
    Box(Modifier.fillMaxSize().safeDrawingPadding()) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(24.dp))
            AppIcon(state.pkg, null, 72.dp)
            Spacer(Modifier.height(20.dp))

            // Opened via emergency access. No early returns in this
            // inline Column: they unbalance Compose groups on recomposition.
            val reason = decision?.primary
            if (state.openedUntil != null) {
                Text(stringResource(R.string.iv_open_until, state.appName, Fmt.time(context, state.openedUntil)), style = FbType.title, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Fb.gutter))
            } else if (state.loading || decision == null) {
                Spacer(Modifier.height(1.dp))
            } else if (!decision.blocked || reason == null) {
                Text(stringResource(R.string.iv_not_blocked, state.appName), style = FbType.title, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Fb.gutter))
                Spacer(Modifier.height(24.dp))
                Column(Modifier.padding(horizontal = Fb.gutter)) { PrimaryButton(stringResource(R.string.iv_open_app, state.appName), onLaunchApp) }
            } else {
                BlockedBody(state, decision, reason, onBackHome, onEndBlock, onEmergency, onCloseEmergency, onReason, onRequestEmergency, onUseEmergency, onCancelEmergency)
            }
        }
    }
    LaunchedEffect(state.openedUntil) { if (state.openedUntil != null) onLaunchApp() }
}

@Composable
private fun BlockedBody(
    state: InterventionState,
    decision: BlockDecision,
    reason: BlockReason,
    onBackHome: () -> Unit,
    onEndBlock: () -> Unit,
    onEmergency: () -> Unit,
    onCloseEmergency: () -> Unit,
    onReason: (String) -> Unit,
    onRequestEmergency: () -> Unit,
    onUseEmergency: () -> Unit,
    onCancelEmergency: () -> Unit,
) {
    val context = LocalContext.current
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val title = when (reason.type) {
            ReasonType.APP_LIMIT -> stringResource(R.string.iv_app_limit, state.appName, Fmt.time(context, reason.until ?: state.now))
            ReasonType.DAILY_LIMIT -> stringResource(R.string.iv_daily_limit, Fmt.time(context, reason.until ?: state.now))
            else -> reason.until?.let { stringResource(R.string.iv_blocked_until, state.appName, Fmt.time(context, it)) }
                ?: stringResource(R.string.iv_blocked_open, state.appName)
        }
        Text(title, style = FbType.title, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Fb.gutter).semantics { heading() })
        Spacer(Modifier.height(10.dp))
        Text(reasonLine(reason, state.session), style = FbType.body.copy(color = Fb.textSecondary), textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Fb.gutter))
        val intention = state.session?.intention
        if (reason.type == ReasonType.SESSION && intention != null) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.iv_working_on, intention), style = FbType.body, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Fb.gutter))
        }

        Spacer(Modifier.height(20.dp))
        FbDivider()
        Spacer(Modifier.height(14.dp))
        reason.until?.let { until ->
            Text(Fmt.left(context, until - state.now), style = FbType.heading, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
            Spacer(Modifier.height(4.dp))
        }
        Text(stringResource(R.string.iv_attempt, Fmt.ordinal(state.attempt)), style = FbType.caption)
        if (decision.strength == Strength.STRICT) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.strict_line), style = FbType.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Fb.gutter))
        }
        Spacer(Modifier.height(14.dp))
        FbDivider()
        Spacer(Modifier.height(20.dp))

        Column(Modifier.fillMaxWidth().padding(horizontal = Fb.gutter), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.emergencyOpen && state.offer.emergency) {
                EmergencyPanel(state, onReason, onRequestEmergency, onUseEmergency, onCancelEmergency, onCloseEmergency)
                Spacer(Modifier.height(8.dp))
                SecondaryButton(stringResource(R.string.action_back_home), onBackHome)
            } else {
                PrimaryButton(stringResource(R.string.action_back_home), onBackHome, leadingIcon = Icons.Outlined.ArrowBack)
                // No "Open anyway": to use the app, end the block itself (with its wait and hold).
                if (reason.type == ReasonType.SESSION && decision.strength == Strength.NORMAL) {
                    val quick = state.session?.type == SessionType.INDEFINITE
                    TextLink(stringResource(if (quick) R.string.iv_stop_quick_block else R.string.iv_end_block), onEndBlock, accent = false)
                }
                if (state.offer.emergency) {
                    // Low emphasis, with its cost visible underneath (spec 2.5).
                    TextLink(stringResource(R.string.emergency_access), onEmergency, accent = false)
                    Text(stringResource(R.string.emergency_cost), style = FbType.caption)
                }
            }
        }

        Spacer(Modifier.height(28.dp))
        AlsoBlockedBy(decision.others, state.session)
        Text(stringResource(R.string.iv_footer), style = FbType.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Fb.gutter))
    }
}

@Composable
private fun AlsoBlockedBy(others: List<BlockReason>, session: SessionInput?) {
    if (others.isEmpty()) return
    Text(
        stringResource(R.string.row_also_blocked_by) + ": " + others.map { reasonName(it, session) }.distinct().joinToString(", "),
        style = FbType.caption, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = Fb.gutter),
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun EmergencyPanel(
    state: InterventionState,
    onReason: (String) -> Unit,
    onRequest: () -> Unit,
    onUse: () -> Unit,
    onCancel: () -> Unit,
    onClose: () -> Unit,
) {
    val context = LocalContext.current
    val pending = state.pending
    Column(Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.emergency_access), style = FbType.heading)
        Spacer(Modifier.height(8.dp))
        when {
            pending == null -> {
                Text(stringResource(R.string.emergency_body, state.appName), style = FbType.body.copy(color = Fb.textSecondary))
                Spacer(Modifier.height(12.dp))
                LabeledField(stringResource(R.string.emergency_reason_label), state.emergencyReason, onReason, stringResource(R.string.emergency_reason_short), maxLength = 200, horizontalPadding = 0.dp)
                Spacer(Modifier.height(16.dp))
                PrimaryButton(stringResource(R.string.emergency_request), onRequest, enabled = FrictionPolicy.emergencyReasonValid(state.emergencyReason))
                TextLink(stringResource(R.string.action_cancel), onClose, accent = false)
            }
            state.now < pending.readyAt -> {
                Text(stringResource(R.string.emergency_waiting, Fmt.time(context, pending.readyAt)), style = FbType.body, modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                Spacer(Modifier.height(4.dp))
                Text(Fmt.left(context, pending.readyAt - state.now), style = FbType.heading)
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.emergency_waiting_body, state.appName, Fmt.time(context, pending.readyAt)), style = FbType.caption)
                TextLink(stringResource(R.string.emergency_cancel), onCancel, accent = false)
            }
            else -> {
                PrimaryButton(stringResource(R.string.emergency_use, state.appName), onUse)
                TextLink(stringResource(R.string.emergency_cancel), onCancel, accent = false)
            }
        }
    }
}
