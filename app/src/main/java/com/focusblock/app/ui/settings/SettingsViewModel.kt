package com.focusblock.app.ui.settings

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.focusblock.app.R
import com.focusblock.app.core.AppGraph
import com.focusblock.app.core.BlockSetup
import com.focusblock.app.core.HealthItem
import com.focusblock.app.core.Notifier
import com.focusblock.app.core.PermissionHealth
import com.focusblock.app.core.ServiceHeartbeat
import com.focusblock.app.core.StartRequest
import com.focusblock.app.core.StartResult
import com.focusblock.app.database.PrefKeys
import com.focusblock.app.database.entity.AppGroup
import com.focusblock.app.database.entity.AppSettings
import com.focusblock.app.database.entity.DiagnosticEvent
import com.focusblock.app.policy.BlockPolicyEngine
import com.focusblock.app.policy.SessionType
import com.focusblock.app.policy.Strength
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class SettingsUi(
    val health: List<HealthItem> = emptyList(),
    val essentials: Set<String> = emptySet(),
    val sets: List<AppGroup> = emptyList(),
    val defaultType: SessionType = SessionType.TIMED,
    val defaultMinutes: Int = 45,
    val defaultStrength: Strength = Strength.NORMAL,
    val notify: Map<Notifier.Kind, Boolean> = emptyMap(),
    val systemNotifications: Boolean = true,
    val widgetAction: String = "last",
    val serviceConnected: Boolean = false,
    val lastEventAt: Long = 0,
    val nextCheck: Long? = null,
    val events: List<DiagnosticEvent> = emptyList(),
    val labels: Map<String, String> = emptyMap(),
    val strictActive: Boolean = false,
    val message: Int? = null,
)

class SettingsViewModel(private val graph: AppGraph) : ViewModel() {
    private val mutable = MutableStateFlow(SettingsUi())
    val state: StateFlow<SettingsUi> = mutable.asStateFlow()

    init {
        viewModelScope.launch { graph.essentials.flow().collect { e -> mutable.update { it.copy(essentials = e, labels = it.labels + e.associateWith(graph.apps::label)) } } }
        viewModelScope.launch { graph.db.appGroupDao().getAllGroups().collect { s -> mutable.update { it.copy(sets = s) } } }
        viewModelScope.launch { graph.db.diagnosticEventDao().recentFlow(30).collect { e -> mutable.update { it.copy(events = e) } } }
        refresh()
    }

    fun refresh() {
        viewModelScope.launch(Dispatchers.IO) {
            val s = graph.db.settingsDao()
            val notify = Notifier.Kind.values().associateWith { graph.notifier.enabled(it) }
            val snap = graph.policy.snapshot()
            val now = graph.clock.now()
            val strict = snap.session?.let { it.strength == Strength.STRICT && (it.plannedEndAt ?: 0L) > now } == true ||
                BlockPolicyEngine.activeRuleReasons(snap, now, graph.clock.zone()).any { it.strength == Strength.STRICT }
            mutable.update {
                it.copy(
                    health = PermissionHealth.items(graph.context),
                    defaultType = SessionType.values().firstOrNull { t -> t.name == s.getValue(PrefKeys.DEFAULT_TYPE) } ?: SessionType.TIMED,
                    defaultMinutes = s.getValue(PrefKeys.DEFAULT_MINUTES)?.toIntOrNull() ?: 45,
                    defaultStrength = if (s.getValue(PrefKeys.DEFAULT_STRENGTH) == Strength.STRICT.name) Strength.STRICT else Strength.NORMAL,
                    notify = notify,
                    systemNotifications = androidx.core.app.NotificationManagerCompat.from(graph.context).areNotificationsEnabled(),
                    widgetAction = s.getValue(PrefKeys.WIDGET_ACTION) ?: "last",
                    serviceConnected = ServiceHeartbeat.connected,
                    lastEventAt = ServiceHeartbeat.lastEventAt,
                    nextCheck = graph.alarms.nextAt,
                    strictActive = strict,
                )
            }
        }
    }

    private fun put(key: String, value: String) {
        viewModelScope.launch { graph.db.settingsDao().insert(AppSettings(key, value)); refresh(); graph.requestRefresh() }
    }

    fun setNotify(kind: Notifier.Kind, on: Boolean) = put(kind.pref, on.toString())
    fun setDefaultType(type: SessionType) {
        put(PrefKeys.DEFAULT_TYPE, type.name)
        if (type == SessionType.INDEFINITE) put(PrefKeys.DEFAULT_STRENGTH, Strength.NORMAL.name)
    }
    fun setDefaultMinutes(minutes: Int) = put(PrefKeys.DEFAULT_MINUTES, minutes.coerceIn(5, 720).toString())
    fun setDefaultStrength(strength: Strength) = put(PrefKeys.DEFAULT_STRENGTH, strength.name)
    fun setWidgetAction(action: String) = put(PrefKeys.WIDGET_ACTION, action)

    /** Changing essentials during Strict Lock would be a bypass, so it is refused. */
    fun setEssentials(packages: List<String>) {
        if (mutable.value.strictActive) { mutable.update { it.copy(message = R.string.essentials_locked) }; return }
        viewModelScope.launch {
            graph.essentials.set(packages.toSet())
            graph.policy.invalidate()
            graph.requestRefresh()
        }
    }

    fun deleteSet(g: AppGroup) { viewModelScope.launch { graph.db.appGroupDao().delete(g) } }
    fun renameSet(g: AppGroup, name: String) { viewModelScope.launch { graph.db.appGroupDao().update(g.copy(name = name.trim().take(40), updatedAt = System.currentTimeMillis())) } }
    fun updateSet(g: AppGroup, packages: List<String>) { viewModelScope.launch { graph.db.appGroupDao().updatePackages(g.id, packages.joinToString(",")) } }

    fun label(pkg: String) = mutable.value.labels[pkg] ?: graph.apps.label(pkg)

    fun clearMessage() = mutable.update { it.copy(message = null) }

    /** Troubleshooting: a 1-minute block on one app, to check the block screen appears. */
    fun testBlock(onStarted: (String) -> Unit) {
        viewModelScope.launch {
            val exempt = graph.policy.exempt()
            val candidate = (graph.sessions.lastSetup()?.packages.orEmpty() + graph.apps.all().map { it.packageName }).firstOrNull { it !in exempt }
            if (candidate == null) { mutable.update { it.copy(message = R.string.start_no_apps) }; return@launch }
            val result = graph.sessions.start(StartRequest(BlockSetup(listOf(candidate), SessionType.TIMED, 1)))
            if (result is StartResult.Started) onStarted(candidate) else mutable.update { it.copy(message = R.string.already_running) }
        }
    }

    /** Data and privacy: export everything FocusBlock stores as JSON. */
    fun export(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            val ok = runCatching {
                val db = graph.db
                val root = JSONObject()
                root.put("exportedAt", graph.clock.now())
                root.put("sessions", JSONArray(db.blockSessionDao().all().map { s ->
                    JSONObject().put("id", s.id).put("packages", s.packages).put("type", s.sessionType).put("strength", s.strength)
                        .put("intention", s.intention).put("startedAt", s.startedAt).put("plannedEndAt", s.plannedEndAt)
                        .put("endedAt", s.endedAt).put("endReason", s.endReason).put("outcome", s.outcome)
                }))
                root.put("attempts", JSONArray(db.attemptDao().all().map { l ->
                    JSONObject().put("app", l.packageName).put("time", l.timestamp).put("reason", l.blockedBy.name).put("rule", l.scheduleName)
                        .put("attempt", l.attemptNumber).put("action", l.action).put("strength", l.strength)
                }))
                root.put("unlocks", JSONArray(db.unlockEventDao().all().map { u ->
                    JSONObject().put("app", u.packageName).put("kind", u.kind).put("status", u.status).put("requestedAt", u.requestedAt)
                        .put("grantedAt", u.grantedAt).put("expiresAt", u.expiresAt).put("reason", u.reasonText).put("strength", u.strength)
                }))
                root.put("routines", JSONArray(db.scheduleDao().getEnabledSchedulesSync().map { r ->
                    JSONObject().put("name", r.name).put("start", r.startTimeMinutes).put("end", r.endTimeMinutes).put("days", r.daysOfWeek)
                        .put("apps", r.blockedPackages).put("strict", r.isStrictMode)
                }))
                root.put("appLimits", JSONArray(db.appLimitDao().all().map { l -> JSONObject().put("name", l.name).put("apps", l.packages).put("minutes", l.minutesPerDay).put("enabled", l.isEnabled) }))
                root.put("essentials", JSONArray(db.essentialAppDao().packages()))
                root.put("recommendations", JSONArray(db.recommendationDao().all().map { r -> JSONObject().put("kind", r.kind).put("status", r.status).put("payload", r.payload) }))
                graph.context.contentResolver.openOutputStream(uri)?.use { it.write(root.toString(2).toByteArray()) } ?: error("no stream")
            }.isSuccess
            mutable.update { it.copy(message = if (ok) R.string.data_exported else R.string.data_export_failed) }
        }
    }

    /** Deletes history only. Rules and settings stay. */
    fun deleteHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            val db = graph.db
            db.blockSessionDao().deleteHistory()
            db.attemptDao().deleteAll()
            db.unlockEventDao().deleteHistory(graph.clock.now())
            db.recommendationDao().deleteAll()
            db.diagnosticEventDao().deleteAll()
            mutable.update { it.copy(message = R.string.data_deleted) }
        }
    }
}
