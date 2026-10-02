package com.focusblock.app.ui.intervention

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.AttemptAction
import com.focusblock.app.core.OverrideManager
import com.focusblock.app.database.entity.UnlockEventEntity
import com.focusblock.app.policy.BlockDecision
import com.focusblock.app.policy.FrictionPolicy
import com.focusblock.app.policy.SessionInput
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class InterventionState(
    val loading: Boolean = true,
    val pkg: String = "",
    val appName: String = "",
    val decision: BlockDecision? = null,
    val session: SessionInput? = null,
    val attempt: Int = 1,
    val offer: FrictionPolicy.Offer = FrictionPolicy.Offer(FrictionPolicy.OpenAnyway.NONE, 0, false),
    /** Seconds left before "Open anyway" can be tapped. */
    val waitLeft: Int = 0,
    val showOpenConfirm: Boolean = false,
    val emergencyOpen: Boolean = false,
    val emergencyReason: String = "",
    val pending: UnlockEventEntity? = null,
    val openedUntil: Long? = null,
    val now: Long = System.currentTimeMillis(),
)

/**
 * The intervention re-evaluates the policy itself rather than trusting the launching service, so
 * what it offers always matches the current decision and strength.
 */
class InterventionViewModel(private val graph: AppGraph, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(InterventionState())
    val state: StateFlow<InterventionState> = mutable.asStateFlow()

    private var logId: Long = 0
    private var ticker: kotlinx.coroutines.Job? = null

    fun bind(pkg: String, logId: Long, attempt: Int) {
        if (pkg == mutable.value.pkg && logId == this.logId) return
        this.logId = logId
        saved.get<String>(KEY_REASON)?.let { r -> mutable.update { it.copy(emergencyReason = r) } }
        mutable.update { it.copy(pkg = pkg, attempt = attempt.coerceAtLeast(1), loading = true, openedUntil = null, showOpenConfirm = false) }
        refresh()
        ticker?.cancel()
        ticker = viewModelScope.launch {
            while (isActive) {
                delay(1_000)
                mutable.update { s -> s.copy(now = System.currentTimeMillis(), waitLeft = (s.waitLeft - 1).coerceAtLeast(0)) }
                if (state.value.now % 30_000 < 1_000) refresh(resetWait = false)
            }
        }
    }

    fun refresh(resetWait: Boolean = true) {
        val pkg = mutable.value.pkg
        viewModelScope.launch {
            val (decision, snap) = graph.enforcer.decide(pkg)
            val offer = FrictionPolicy.offer(decision, mutable.value.attempt)
            val pending = graph.db.unlockEventDao().pending(pkg)
            mutable.update {
                it.copy(
                    loading = false,
                    appName = graph.apps.label(pkg),
                    decision = decision,
                    session = snap.session,
                    offer = offer,
                    waitLeft = if (resetWait) offer.waitSeconds else it.waitLeft,
                    pending = pending,
                    emergencyOpen = it.emergencyOpen || pending != null,
                    now = System.currentTimeMillis(),
                )
            }
        }
    }

    fun backHome() {
        viewModelScope.launch { graph.enforcer.setAction(logId, AttemptAction.RETURNED) }
    }

    fun requestOpenAnyway() {
        val s = mutable.value
        if (s.waitLeft > 0) return
        when (s.offer.openAnyway) {
            FrictionPolicy.OpenAnyway.AFTER_WAIT_AND_CONFIRM -> mutable.update { it.copy(showOpenConfirm = true) }
            FrictionPolicy.OpenAnyway.NONE -> Unit
            else -> confirmOpenAnyway()
        }
    }

    fun dismissOpenConfirm() = mutable.update { it.copy(showOpenConfirm = false) }

    fun confirmOpenAnyway() {
        mutable.update { it.copy(showOpenConfirm = false) }
        viewModelScope.launch {
            when (val r = graph.overrides.openAnyway(mutable.value.pkg, logId)) {
                is OverrideManager.Result.Granted -> mutable.update { it.copy(openedUntil = r.until) }
                else -> refresh()
            }
        }
    }

    fun openEmergency() = mutable.update { it.copy(emergencyOpen = true) }
    fun closeEmergency() = mutable.update { it.copy(emergencyOpen = false) }

    fun setReason(text: String) {
        saved[KEY_REASON] = text
        mutable.update { it.copy(emergencyReason = text.take(200)) }
    }

    fun requestEmergency() {
        val s = mutable.value
        if (!FrictionPolicy.emergencyReasonValid(s.emergencyReason)) return
        viewModelScope.launch {
            graph.overrides.requestEmergency(s.pkg, s.emergencyReason, logId)
            refresh(resetWait = false)
        }
    }

    fun useEmergency() {
        viewModelScope.launch {
            when (val r = graph.overrides.useEmergency(mutable.value.pkg)) {
                is OverrideManager.Result.Granted -> mutable.update { it.copy(openedUntil = r.until) }
                else -> refresh(resetWait = false)
            }
        }
    }

    fun cancelEmergency() {
        viewModelScope.launch {
            graph.overrides.cancelEmergency(mutable.value.pkg)
            mutable.update { it.copy(pending = null, emergencyOpen = false) }
        }
    }

    companion object { private const val KEY_REASON = "reason" }
}
