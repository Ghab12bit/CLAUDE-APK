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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
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

/** A rule that also covers the running block's apps, from [start]; a blank [name] is Bedtime. */
data class AlsoBlocked(val name: String, val start: Long)

/** A short confirmation the Block tab shows once, as a snackbar. */
sealed interface Notice {
    data class Extended(val until: Long) : Notice
    data class AppsAdded(val count: Int) : Notice
}

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
    val alsoBlockedBy: List<AlsoBlocked> = emptyList(),
    val blockedOpenings: Int = 0,
    val busy: Boolean = false,
    val message: Int? = null,
    /** Apps worth blocking from the last 7 days of use (see SmartApps), not yet in the draft. */
    val suggestions: List<com.focusblock.app.policy.SmartApps.Suggestion> = emptyList(),
    /** Set when the notification or widget asked to end the block; the screen opens the end-early sheet. */
    val endEarlyRequested: Boolean = false,
    val notice: Notice? = null,
    /** Today's Strict emergency stop is used (one a day). */
    val emergencyStopUsed: Boolean = false,
)

class BlockViewModel(private val graph: AppGraph, private val saved: SavedStateHandle) : ViewModel() {
    private val mutable = MutableStateFlow(BlockUi())
    val state: StateFlow<BlockUi> = mutable.asStateFlow()

    init {
        // Restore an in-progress draft after process death; otherwise see initialDraft().
        val restored = restoreDraft()
        restored?.let { d -> mutable.update { it.copy(draft = d) } }
        viewModelScope.launch {
            // Decided before loading ends, so the first draft shown never jumps or mixes sources.
            if (restored == null) runCatching { initialDraft() }.getOrNull()?.let { d -> mutable.update { it.copy(draft = d) } }
            combine(graph.sessions.activeFlow(), graph.sessions.awaitingOutcomeFlow(), graph.sessions.lastSetupFlow(), graph.essentials.flow()) { a, w, l, e ->
                Quad(a, w, l, e)
            }.collect { (active, awaiting, last, essentials) ->
                mutable.update { s ->
                    s.copy(
                        loading = false,
                        session = active,
                        input = active?.let(graph.sessions::toInput),
                        awaitingOutcome = awaiting,
                        last = last,
                        essentials = essentials,
                        // A stale "End early" (tapped after the block ended) must not reach the next block.
                        endEarlyRequested = s.endEarlyRequested && active != null,
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
        viewModelScope.launch { followDefaults() }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    /** Settings' Default block as stored; every field is null until the user sets one there. */
    private data class Defaults(val type: String?, val minutes: String?, val strength: String?) {
        val isSet get() = type != null || minutes != null || strength != null
    }

    private fun Defaults.draftType() = SessionType.values().firstOrNull { it.name == type }?.forDraft() ?: SessionType.TIMED
    private fun Defaults.draftMinutes() = minutes?.toIntOrNull()?.takeIf { it in 1..720 } ?: 45
    private fun Defaults.draftStrict() = strength == Strength.STRICT.name

    private fun defaultsFlow(): Flow<Defaults> {
        val s = graph.db.settingsDao()
        return combine(s.getValueFlow(PrefKeys.DEFAULT_TYPE), s.getValueFlow(PrefKeys.DEFAULT_MINUTES), s.getValueFlow(PrefKeys.DEFAULT_STRENGTH)) { t, m, st ->
            Defaults(t, m, st)
        }.distinctUntilChanged()
    }

    /**
     * The draft a fresh Block tab starts from. A Default block saved in Settings defines it (type,
     * length, strength; intervals at their standard 25/5/4); without one, the last block started
     * does. Fields never mix between the two. Apps are the picker's saved selection, else the last
     * block's apps.
     */
    private suspend fun initialDraft(): Draft {
        val s = graph.db.settingsDao()
        val defaults = Defaults(s.getValue(PrefKeys.DEFAULT_TYPE), s.getValue(PrefKeys.DEFAULT_MINUTES), s.getValue(PrefKeys.DEFAULT_STRENGTH))
        val last = graph.sessions.lastSetup()
        val base = Draft(packages = graph.sessions.savedSelection().ifEmpty { last?.packages.orEmpty() })
        return when {
            defaults.isSet -> base.copy(type = defaults.draftType(), minutes = defaults.draftMinutes(), strict = defaults.draftStrict())
            last != null -> draftFrom(last, base)
            else -> base
        }
    }

    /** Changing the Default block in Settings while the app is open updates the draft's changed fields. */
    private suspend fun followDefaults() {
        var previous: Defaults? = null
        defaultsFlow().collect { d ->
            val before = previous
            previous = d
            if (before == null) return@collect // The value at launch, already used by initialDraft().
            edit { draft ->
                var next = draft
                if (d.type != before.type) next = next.copy(type = d.draftType())
                if (d.minutes != before.minutes) next = next.copy(minutes = d.draftMinutes())
                if (d.strength != before.strength) next = next.copy(strict = d.draftStrict())
                next
            }
        }
    }

    /** The Block tab sets up timed blocks and intervals; "until I stop" is the Quick block card. */
    private fun SessionType.forDraft() = if (this == SessionType.INDEFINITE) SessionType.TIMED else this

    private fun draftFrom(last: BlockSetup, current: Draft) = current.copy(
        type = last.type.forDraft(), minutes = last.minutes.takeIf { it in 1..720 } ?: current.minutes, focus = last.focusMinutes,
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
            val suggestions = runCatching { graph.smartApps.suggestions(8) }.getOrDefault(emptyList())
            val emergencyUsed = graph.sessions.emergencyStopUsedToday()
            val labels = HashMap(mutable.value.labels)
            (mutable.value.draft.packages + (session?.packages ?: emptySet()) + (mutable.value.last?.packages ?: emptyList()) + suggestions.map { it.pkg })
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
                    suggestions = suggestions,
                    emergencyStopUsed = emergencyUsed,
                )
            }
        }
    }

    /** Rules that also cover the block's apps during the block (spec 4.3 "Also blocked by"). */
    private suspend fun overlapping(session: SessionInput, now: Long): List<AlsoBlocked> {
        val snap = graph.policy.snapshot()
        val zone = graph.clock.zone()
        val end = session.plannedEndAt ?: (now + 12 * 3_600_000L)
        val out = ArrayList<AlsoBlocked>()
        snap.routines.filter { it.enabled && it.packages.any { p -> p in session.packages } }.forEach { r ->
            val w = r.window ?: return@forEach
            val start = if (w.isActive(now, zone)) now else w.nextStartAfter(now, zone)
            if (start != null && start < end) out += AlsoBlocked(r.name, start)
        }
        snap.bedtime?.takeIf { it.enabled }?.window?.let { w ->
            val start = if (w.isActive(now, zone)) now else w.nextStartAfter(now, zone)
            if (start != null && start < end) out += AlsoBlocked("", start)
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

    /** Apps a quick block would use: the chosen apps, or else the top suggestions. */
    fun quickApps(): List<String> = mutable.value.draft.packages.ifEmpty { mutable.value.suggestions.take(QUICK_SUGGESTED).map { it.pkg } }

    /** Quick block: no timer, Normal strength, runs until you stop it. */
    fun startQuick() {
        val apps = quickApps()
        if (apps.isEmpty()) return
        run(BlockSetup(apps, SessionType.INDEFINITE, strength = Strength.NORMAL), mutable.value.draft.intention)
    }

    fun addSuggested(pkg: String) = setPackages((mutable.value.draft.packages + pkg).distinct())

    fun addAllSuggested() = setPackages((mutable.value.draft.packages + mutable.value.suggestions.map { it.pkg }).distinct())

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

    /** Turns the running Normal block (quick or timed) into a Strict one. */
    fun makeStrict() {
        viewModelScope.launch {
            graph.sessions.makeStrict()
            refreshDerived()
        }
    }

    /** Once-a-day emergency stop of a Strict block (Block tab only). */
    fun emergencyStop() {
        viewModelScope.launch {
            if (graph.sessions.emergencyStop() == com.focusblock.app.core.EmergencyStopResult.USED_TODAY) {
                mutable.update { it.copy(message = com.focusblock.app.R.string.emergency_stop_used_message) }
            }
            refreshDerived()
        }
    }

    fun extend() {
        viewModelScope.launch {
            if (!graph.sessions.extend()) return@launch
            graph.sessions.active()?.plannedEndAt?.let { until -> mutable.update { it.copy(notice = Notice.Extended(until)) } }
        }
    }

    /** Ignored when nothing is running (a stale notification or widget); kept while loading, until the block is known. */
    fun requestEndEarly() = mutable.update { if (!it.loading && it.session == null) it else it.copy(endEarlyRequested = true) }
    fun clearEndEarlyRequest() = mutable.update { it.copy(endEarlyRequested = false) }

    /** Adds apps to the running block (allowed in Strict Lock too: it only makes the block stricter). */
    fun addApps(packages: List<String>) {
        viewModelScope.launch {
            val added = graph.sessions.addApps(packages)
            if (added > 0) mutable.update { it.copy(notice = Notice.AppsAdded(added)) }
            refreshDerived()
        }
    }

    fun clearNotice() = mutable.update { it.copy(notice = null) }

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
            type = SessionType.values().firstOrNull { it.name == v[1] }?.forDraft() ?: SessionType.TIMED,
            minutes = v[2].toIntOrNull() ?: 45, focus = v[3].toIntOrNull() ?: 25, rest = v[4].toIntOrNull() ?: 5,
            rounds = v[5].toIntOrNull() ?: 4, strict = v[6].toBoolean(), intention = v[7],
        )
    }

    companion object {
        private const val KEY_DRAFT = "draft"
        val PRESETS = listOf(25, 45, 60)
        const val QUICK_SUGGESTED = 5
    }
}

/** Phase of an active session for display. */
fun SessionInput.displayState(now: Long): SessionClock.State = SessionClock.state(this, now)

fun ReasonType.isRule() = this == ReasonType.ROUTINE || this == ReasonType.BEDTIME
