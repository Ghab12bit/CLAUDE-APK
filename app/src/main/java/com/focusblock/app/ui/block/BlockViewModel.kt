package com.focusblock.app.ui.block

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.BlockSetup
import com.focusblock.app.core.EndResult
import com.focusblock.app.core.HealthState
import com.focusblock.app.core.PermissionHealth
import com.focusblock.app.core.Reject
import com.focusblock.app.core.Requirement
import com.focusblock.app.core.StartRequest
import com.focusblock.app.core.StartResult
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.database.entity.BlockSessionEntity
import com.focusblock.app.policy.BlockPolicyEngine
import com.focusblock.app.policy.BlockReason
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.ReasonType
import com.focusblock.app.policy.SessionClock
import com.focusblock.app.policy.SessionInput
import com.focusblock.app.policy.SessionOutcome
import com.focusblock.app.policy.SessionType
import com.focusblock.app.policy.Strength
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** The setup being edited on the Block tab. Saved in [SavedStateHandle] so it survives process death. */
data class Draft(
    val packages: List<String> = emptyList(),
    val type: SessionType = SessionType.TIMED,
    val minutes: Int = 45,
    val focus: Int = 25,
    val rest: Int = 5,
    val rounds: Int = 4,
    val strict: Boolean = false,
    val intention: String = "",
) {
    fun toSetup() = BlockSetup(packages, type, minutes, focus, rest, rounds, if (strict && type != SessionType.INDEFINITE) Strength.STRICT else Strength.NORMAL)
}

data class NextRule(val name: String, val at: Long)

data class BlockUi(
    val loading: Boolean = true,
    val draft: Draft = Draft(),
    val last: BlockSetup? = null,
    val session: BlockSessionEntity? = null,
    val input: SessionInput? = null,
    val awaitingOutcome: BlockSessionEntity? = null,
    val labels: Map<String, String> = emptyMap(),
    val essentials: Set<String> = emptySet(),
    val missing: Requirement? = null,
    val accessibility: HealthState = HealthState.OK,
    val activeRule: BlockReason? = null,
    val nextRule: NextRule? = null,
    val alsoBlockedBy: List<String> = emptyList(),
    val blockedOpenings: Int = 0,
    val busy: Boolean = false,
    val message: Int? = null,
    /** Set when the notification or widget asked to end the block; the screen opens the end-early sheet. */
    val endEarlyRequested: Boolean = false,
)

class BlockViewModel(private val graph: AppGraph, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(BlockUi())
    val state: StateFlow<BlockUi> = mutable.asStateFlow()

    init {
        // Restore an in-progress draft after process death; otherwise start from the last setup.
        restoreDraft()?.let { d -> mutable.update { it.copy(draft = d) } }
        viewModelScope.launch {
            combine(graph.sessions.activeFlow(), graph.sessions.awaitingOutcomeFlow(), graph.sessions.lastSetupFlow(), graph.essentials.flow()) { a, w, l, e ->
                Quad(a, w, l, e)
            }.collect { (active, awaiting, last, essentials) ->
                val hadDraft = saved.contains(KEY_DRAFT)
                mutable.update { s ->
                    s.copy(
                        loading = false,
                        session = active,
                        input = active?.let(graph.sessions::toInput),
                        awaitingOutcome = awaiting,
                        last = last,
                        essentials = essentials,
                        draft = if (!hadDraft && last != null && s.draft.packages.isEmpty()) draftFrom(last, s.draft) else s.draft,
                    )
                }
                refreshDerived()
            }
        }
        viewModelScope.launch {
            while (isActive) {
                delay(15_000)
                graph.sessions.reconcile()
                refreshDerived()
            }
        }
        viewModelScope.launch { applyDefaults() }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private suspend fun applyDefaults() {
        if (saved.contains(KEY_DRAFT) || mutable.value.last != null) return
        val s = graph.db.settingsDao()
        val type = SessionType.values().firstOrNull { it.name == s.getValue(PrefKeys.DEFAULT_TYPE) } ?: SessionType.TIMED
        val strict = s.getValue(PrefKeys.DEFAULT_STRENGTH) == Strength.STRICT.name
        val minutes = s.getValue(PrefKeys.DEFAULT_MINUTES)?.toIntOrNull() ?: 45
        val selection = graph.sessions.savedSelection()
        mutable.update { it.copy(draft = it.draft.copy(type = type, strict = strict, minutes = minutes, packages = if (it.draft.packages.isEmpty()) selection else it.draft.packages)) }
    }

    private fun draftFrom(last: BlockSetup, current: Draft) = current.copy(
        packages = last.packages, type = last.type, minutes = last.minutes, focus = last.focusMinutes,
        rest = last.breakMinutes, rounds = last.rounds.coerceAtLeast(2), strict = last.strength == Strength.STRICT,
    )

    /** Status line, next rule, overlap rows and health; recomputed on changes and every 15 s. */
    fun refreshDerived() {
        viewModelScope.launch {
            val ctx = graph.context
            val snap = graph.policy.snapshot()
            val now = graph.clock.now()
            val zone = graph.clock.zone()
            val active = BlockPolicyEngine.activeRuleReasons(snap, now, zone).firstOrNull()
            val next = buildList {
                snap.routines.filter { it.enabled && it.window != null && !it.imported }.forEach { r ->
                    r.window!!.nextStartAfter(now, zone)?.let { add(NextRule(r.name, it)) }
                }
                snap.bedtime?.takeIf { it.enabled }?.window?.nextStartAfter(now, zone)?.let { add(NextRule("", it)) }
            }.minByOrNull { it.at }
            val session = mutable.value.input
            val also = if (session != null) overlapping(session, now) else emptyList()
            val openings = mutable.value.session?.let { graph.db.attemptDao().since(it.startedAt).count { log -> log.sessionId == it.id } } ?: 0
            val labels = HashMap(mutable.value.labels)
            (mutable.value.draft.packages + (session?.packages ?: emptySet()) + (mutable.value.last?.packages ?: emptyList()))
                .forEach { if (it !in labels) labels[it] = graph.apps.label(it) }
            mutable.update {
                it.copy(
                    missing = PermissionHealth.missingRequired(ctx),
                    accessibility = PermissionHealth.accessibilityState(ctx),
                    activeRule = active,
                    nextRule = next,
                    alsoBlockedBy = also,
                    blockedOpenings = openings,
                    labels = labels,
                )
            }
        }
    }

    /** Rules that also cover the block's apps during the block (spec 4.3 "Also blocked by"). */
    private suspend fun overlapping(session: SessionInput, now: Long): List<String> {
        val snap = graph.policy.snapshot()
        val zone = graph.clock.zone()
        val end = session.plannedEndAt ?: (now + 12 * 3_600_000L)
        val out = ArrayList<String>()
        snap.routines.filter { it.enabled && it.packages.any { p -> p in session.packages } }.forEach { r ->
            val w = r.window ?: return@forEach
            val start = if (w.isActive(now, zone)) now else w.nextStartAfter(now, zone)
            if (start != null && start < end) out += "${r.name}|$start"
        }
        snap.bedtime?.takeIf { it.enabled }?.window?.let { w ->
            val start = if (w.isActive(now, zone)) now else w.nextStartAfter(now, zone)
            if (start != null && start < end) out += "|$start"
        }
        return out
    }

    // ---- Draft edits ------------------------------------------------------------------------

    private fun edit(block: (Draft) -> Draft) {
        mutable.update { it.copy(draft = block(it.draft), message = null) }
        persistDraft()
    }

    fun setPackages(packages: List<String>) {
        edit { it.copy(packages = packages) }
        refreshDerived()
        viewModelScope.launch { graph.sessions.saveSelection(packages) }
    }
    fun setType(type: SessionType) = edit { it.copy(type = type, strict = if (type == SessionType.INDEFINITE) false else it.strict) }
    fun setMinutes(minutes: Int) = edit { it.copy(minutes = minutes.coerceIn(1, 720)) }
    fun setFocus(minutes: Int) = edit { it.copy(focus = minutes.coerceIn(5, 120)) }
    fun setRest(minutes: Int) = edit { it.copy(rest = minutes.coerceIn(1, 30)) }
    fun setRounds(rounds: Int) = edit { it.copy(rounds = rounds.coerceIn(2, 8)) }
    fun setStrict(strict: Boolean) = edit { it.copy(strict = strict && it.type != SessionType.INDEFINITE) }
    fun setIntention(text: String) = edit { it.copy(intention = text.take(80)) }
    fun clearMessage() = mutable.update { it.copy(message = null) }

    // ---- Actions ----------------------------------------------------------------------------

    fun start() = run(mutable.value.draft.toSetup(), mutable.value.draft.intention)

    fun repeatLast() {
        val last = mutable.value.last ?: return
        run(last, mutable.value.draft.intention)
    }

    private fun run(setup: BlockSetup, intention: String) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, message = null) }
        viewModelScope.launch {
            val result = graph.sessions.start(StartRequest(setup, intention))
            mutable.update {
                it.copy(
                    busy = false,
                    message = when (result) {
                        is StartResult.Started -> null
                        is StartResult.Rejected -> when (result.reason) {
                            Reject.NO_APPS -> com.focusblock.app.R.string.start_no_apps
                            Reject.ALREADY_RUNNING -> com.focusblock.app.R.string.already_running
                            Reject.STRICT_NEEDS_END -> com.focusblock.app.R.string.strict_lock_needs_end
                            Reject.INVALID_LENGTH -> com.focusblock.app.R.string.error_minutes
                        }
                    },
                )
            }
            if (result is StartResult.Started) {
                // The intention belongs to the block that just started; the next one starts empty.
                edit { it.copy(intention = "") }
                graph.notifier.cancelEnded()
            }
        }
    }

    fun end() {
        viewModelScope.launch {
            if (graph.sessions.end() == EndResult.STRICT_LOCKED) mutable.update { it.copy(message = com.focusblock.app.R.string.strict_cannot_end) }
        }
    }

    fun extend() { viewModelScope.launch { graph.sessions.extend() } }

    fun requestEndEarly() = mutable.update { it.copy(endEarlyRequested = true) }
    fun clearEndEarlyRequest() = mutable.update { it.copy(endEarlyRequested = false) }

    /** Adds apps to the running block (allowed in Strict Lock too: it only makes the block stricter). */
    fun addApps(packages: List<String>) {
        viewModelScope.launch {
            graph.sessions.addApps(packages)
            refreshDerived()
        }
    }

    fun outcome(outcome: SessionOutcome) {
        val id = mutable.value.awaitingOutcome?.id ?: return
        viewModelScope.launch {
            if (outcome == SessionOutcome.EXTENDED) graph.sessions.extendFinished(id) else graph.sessions.recordOutcome(id, outcome)
            graph.notifier.cancelEnded()
        }
    }

    fun startOfToday(): Long = PolicyTime.startOfDay(graph.clock.now(), graph.clock.zone())

    fun label(pkg: String): String = mutable.value.labels[pkg] ?: graph.apps.label(pkg)

    fun isEssential(pkg: String) = pkg in mutable.value.essentials

    // ---- Saved state ---------------------------------------------------------------------------

    private fun persistDraft() {
        val d = mutable.value.draft
        saved[KEY_DRAFT] = arrayListOf(d.packages.joinToString(","), d.type.name, d.minutes.toString(), d.focus.toString(), d.rest.toString(), d.rounds.toString(), d.strict.toString(), d.intention)
    }

    private fun restoreDraft(): Draft? {
        val v = saved.get<ArrayList<String>>(KEY_DRAFT) ?: return null
        if (v.size < 8) return null
        return Draft(
            packages = v[0].split(',').filter { it.isNotBlank() },
            type = SessionType.values().firstOrNull { it.name == v[1] } ?: SessionType.TIMED,
            minutes = v[2].toIntOrNull() ?: 45, focus = v[3].toIntOrNull() ?: 25, rest = v[4].toIntOrNull() ?: 5,
            rounds = v[5].toIntOrNull() ?: 4, strict = v[6].toBoolean(), intention = v[7],
        )
    }

    companion object {
        private const val KEY_DRAFT = "draft"
        val PRESETS = listOf(25, 45, 60)
    }
}

/** Phase of an active session for display. */
fun SessionInput.displayState(now: Long): SessionClock.State = SessionClock.state(this, now)

fun ReasonType.isRule() = this == ReasonType.ROUTINE || this == ReasonType.BEDTIME
