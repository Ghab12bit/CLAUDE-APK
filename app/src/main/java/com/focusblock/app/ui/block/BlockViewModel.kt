package com.focusblock.app.ui.block

import android.app.Application
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.focusblock.app.blocking.*
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.*
import com.focusblock.app.service.AppBlockingService
import com.focusblock.app.utils.AppUtils
import com.focusblock.app.utils.PermissionUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import java.util.Calendar
import javax.inject.Inject

data class BlockSetup(val packages: List<String> = emptyList(), val mode: String = "timed",
    val minutes: Int = 45, val rest: Int = 5, val rounds: Int = 4, val strict: Boolean = false, val budget: Int = 1) {
    fun json(): String = JSONObject().put("apps", packages.joinToString(",")).put("mode", mode)
        .put("minutes", minutes).put("rest", rest).put("rounds", rounds).put("strict", strict).put("budget", budget).toString()
    companion object {
        fun parse(s: String?): BlockSetup = runCatching {
            val j = JSONObject(s ?: "{}")
            BlockSetup(QuickBlockPolicy.packages(j.optString("apps")).toList(), j.optString("mode", "timed"),
                j.optInt("minutes", 45), j.optInt("rest", 5), j.optInt("rounds", 4), j.optBoolean("strict"), j.optInt("budget", 1))
        }.getOrDefault(BlockSetup())
    }
}

data class BlockUiState(
    val loading: Boolean = true, val apps: List<AppUtils.AppInfo> = emptyList(),
    val essential: Set<String> = emptySet(), val session: QuickBlockSession? = null,
    val metadata: BlockSessionMetadata = BlockSessionMetadata(), val last: BlockSetup = BlockSetup(),
    val schedules: List<Schedule> = emptyList(), val limits: List<AppTimeLimit> = emptyList(),
    val logs: List<BlockLog> = emptyList(), val sessions: List<QuickBlockSession> = emptyList(),
    val usage: Map<String, Long>? = null, val yesterday: Map<String, Long>? = null,
    val ready: Boolean = false, val now: Long = System.currentTimeMillis(),
    val reminder: String = "", val busy: Boolean = false, val error: String? = null,
    val importedRules: List<ImportedRuleStore.Rule> = emptyList(), val configurationLocked: Boolean = false,
    val savedSelection: List<String> = emptyList()
)

@HiltViewModel
class BlockViewModel @Inject constructor(private val app: Application) : AndroidViewModel(app) {
    private val db = FocusBlockDatabase.getDatabase(app)
    private val mutable = MutableStateFlow(BlockUiState())
    val state = mutable.asStateFlow()

    init {
        observe { db.quickBlockSessionDao().getActiveSession().collect { s -> mutable.update { it.copy(session = s) } } }
        observe { db.quickBlockSessionDao().getAllSessions().collect { s -> mutable.update { it.copy(sessions = s) } } }
        observe { db.scheduleDao().getAllSchedules().collect { s -> mutable.update { it.copy(schedules = s) } } }
        observe { db.appTimeLimitDao().getAllTimeLimits().collect { s -> mutable.update { it.copy(limits = s) } } }
        observe { db.blockLogDao().getAllLogs().collect { s -> mutable.update { it.copy(logs = s) } } }
        observe { db.settingsDao().getValueFlow(BlockSessionStore.KEY).collect { s -> mutable.update { it.copy(metadata = BlockSessionMetadata.parse(s)) } } }
        observe { db.settingsDao().getValueFlow(BlockSessionStore.LAST).collect { s ->
            val saved = if (s == null) BlockSetup(packages = QuickBlockPolicy.packages(db.settingsDao().getValue(AppSettings.KEY_QUICK_BLOCK_SAVED_APPS).orEmpty()).toList()) else BlockSetup.parse(s)
            mutable.update { it.copy(last = saved) }
        } }
        observe { db.settingsDao().getValueFlow(BlockSessionStore.REMINDER).collect { s -> mutable.update { it.copy(reminder = s.orEmpty()) } } }
        observe {
            val apps = AppUtils.getInstalledApps(app, true)
            val essentials = BlockSessionStore.requiredPackages(app) + db.essentialAppWhitelistDao().getWhitelistedPackageNames() +
                db.blockedAppDao().getAllowlistApps().first().map { it.packageName }
            val savedSelection = QuickBlockPolicy.packages(db.settingsDao().getValue(AppSettings.KEY_QUICK_BLOCK_SAVED_APPS).orEmpty()).toList()
            val last = BlockSetup.parse(db.settingsDao().getValue(BlockSessionStore.LAST))
            mutable.update { it.copy(apps = apps, essential = essentials, loading = false, savedSelection = savedSelection, last = last) }
        }
        observe {
            var ticks = 0
            while (isActive) {
                val now = System.currentTimeMillis()
                val ready = PermissionUtils.hasAccessibilityServiceEnabled(app) && PermissionUtils.hasUsageStatsPermission(app) && PermissionUtils.hasOverlayPermission(app)
                val imported = ImportedRuleStore.rules(db)
                val locked = ImportedRuleStore.configurationLocked(db)
                mutable.update { it.copy(now = now, ready = ready, importedRules = imported, configurationLocked = locked) }
                val s = db.quickBlockSessionDao().getActiveSessionSync()
                if (s != null && QuickBlockPolicy.isExpired(s.endTime, now)) db.quickBlockSessionDao().deactivate(s.id)
                if (ticks++ % 30 == 0) refreshUsage(now)
                delay(1000)
            }
        }
    }

    private fun observe(block: suspend CoroutineScope.() -> Unit) = viewModelScope.launch(Dispatchers.IO) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Exception) {
            mutable.update { it.copy(loading = false, error = "Could not read saved state. ${e.localizedMessage.orEmpty()}") }
        }
    }
    private fun mutate(block: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, error = null) }
        viewModelScope.launch(Dispatchers.IO) {
            try { block(); AppBlockingService.update(app) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.update { it.copy(error = e.localizedMessage ?: "Could not save. Please try again.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }
    fun clearError() { mutable.update { it.copy(error = null) } }
    fun toggleImported(rule: ImportedRuleStore.Rule) = mutate { ImportedRuleStore.toggle(db, rule) }
    fun saveImported(rule: ImportedRuleStore.Rule) = mutate {
        db.withTransaction { ImportedRuleStore.save(db, rule.copy(packages = QuickBlockPolicy.packages(rule.packages).filter { it !in state.value.essential }.joinToString(","))) }
    }
    fun start(setup: BlockSetup) = mutate {
        check(state.value.ready) { "Restore accessibility, usage access and overlay permissions first." }
        require(setup.minutes in 1..720 && setup.rest in 1..30 && setup.rounds in 2..8) { "Check the duration and cycle settings." }
        require(setup.mode in setOf("timed", "cycles", "manual"))
        require(!(setup.mode == "manual" && setup.strict)) { "Strict Lock needs a timed end." }
        val installed = state.value.apps.map { it.packageName }.toSet()
        val packages = QuickBlockPolicy.sanitizePackages(setup.packages).filter { it in installed && it !in state.value.essential }
        require(packages.isNotEmpty()) { "Select at least one installed, non-essential app." }
        db.withTransaction {
            check(db.quickBlockSessionDao().getActiveSessionSync() == null) { "A block is already running." }
            val now = System.currentTimeMillis()
            val total = if (setup.mode == "cycles") setup.minutes * setup.rounds + setup.rest * (setup.rounds - 1) else setup.minutes
            val id = db.quickBlockSessionDao().insert(QuickBlockSession(startTime = now,
                endTime = if (setup.mode == "manual") null else now + total * 60000L,
                blockedPackages = packages.joinToString(","), previouslyBlockedPackages = db.blockedAppDao().getBlockedPackageNames().joinToString(",")))
            val meta = BlockSessionMetadata(id, setup.strict, if (setup.mode == "cycles") setup.minutes else 0,
                setup.rest, if (setup.mode == "cycles") setup.rounds else 1, setup.budget.coerceIn(0, 1))
            db.settingsDao().insert(AppSettings(BlockSessionStore.KEY, meta.json()))
            db.settingsDao().insert(AppSettings(BlockSessionStore.LAST, setup.copy(packages = packages).json()))
            db.settingsDao().insert(AppSettings(AppSettings.KEY_QUICK_BLOCK_SAVED_APPS, packages.joinToString(",")))
        }
    }
    fun stop() = mutate {
        db.withTransaction {
            check(!BlockSessionStore.isLocked(db)) { "This block cannot be ended before its timer." }
            check(!ImportedRuleStore.configurationLocked(db)) { "Your existing configuration lock is active." }
            check((db.settingsDao().getValue(AppSettings.KEY_STRICT_MODE_END_TIME)?.toLongOrNull() ?: 0) <= System.currentTimeMillis()) { "Existing Strict Mode is still active." }
            check(db.settingsDao().getValue(AppSettings.KEY_HARD_MODE_ENABLED) != "true") { "Use the existing Hard Mode unlock in advanced controls." }
            val s = db.quickBlockSessionDao().getActiveSessionSync() ?: return@withTransaction
            db.quickBlockSessionDao().update(s.copy(isActive = false, endTime = System.currentTimeMillis()))
        }
    }
    fun extend() = mutate {
        db.withTransaction {
            val s = db.quickBlockSessionDao().getActiveSessionSync() ?: return@withTransaction
            val end = s.endTime ?: return@withTransaction
            check(end > System.currentTimeMillis()) { "This block has already ended." }
            db.quickBlockSessionDao().update(s.copy(endTime = end + 15 * 60000L))
        }
    }
    fun saveSelection(packages: List<String>) = mutate {
        db.settingsDao().insert(AppSettings(AppSettings.KEY_QUICK_BLOCK_SAVED_APPS, packages.joinToString(",")))
        mutable.update { it.copy(savedSelection = packages) }
    }
    private fun ruleLocked(s: Schedule): Boolean {
        val c = Calendar.getInstance()
        val day = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1
        return s.isStrictMode && SchedulePolicy.isActive(s, c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), day)
    }
    fun saveRule(rule: Schedule) = mutate {
        check(!ImportedRuleStore.configurationLocked(db)) { "Your existing configuration lock is active." }
        require(rule.name.isNotBlank() && rule.blockedPackages.isNotBlank()) { "Name the rule and choose apps." }
        require(rule.startTimeMinutes in 0..1439 && rule.endTimeMinutes in 0..1439 && rule.startTimeMinutes != rule.endTimeMinutes && rule.daysOfWeek.isNotBlank()) { "Choose different start/end times and repeat days." }
        db.withTransaction {
            val old = db.scheduleDao().getSchedule(rule.id)
            check(old == null || !ruleLocked(old)) { "This rule is locked until its active window ends." }
            db.scheduleDao().insert(rule.copy(blockedPackages = QuickBlockPolicy.packages(rule.blockedPackages).filter { it !in state.value.essential }.joinToString(",")))
        }
    }
    fun toggleRule(rule: Schedule, enabled: Boolean) = saveRule(rule.copy(isEnabled = enabled))
    fun saveLimit(limit: AppTimeLimit) = mutate {
        check(!ImportedRuleStore.configurationLocked(db)) { "Your existing configuration lock is active." }
        require(limit.dailyLimitMinutes in 1..720 && limit.packageName !in state.value.essential) { "Choose a non-essential app and 1–720 minutes." }
        db.appTimeLimitDao().insert(limit.copy(updatedAt = System.currentTimeMillis()))
    }
    fun reminder(value: String) = mutate { db.settingsDao().insert(AppSettings(BlockSessionStore.REMINDER, value)) }
    fun essentials(packages: Set<String>) = mutate {
        check(!BlockSessionStore.isLocked(db)) { "Essentials cannot change during Strict Lock." }
        check(!ImportedRuleStore.configurationLocked(db) && state.value.schedules.none(::ruleLocked)) { "An active rule locks this configuration." }
        val required = BlockSessionStore.requiredPackages(app)
        val old = db.blockedAppDao().getAllowlistApps().first()
        old.filter { it.packageName !in packages && it.packageName !in required }.forEach { db.blockedAppDao().update(it.copy(isInAllowlist = false)) }
        packages.filter { it !in required }.forEach { pkg ->
            val existing = db.blockedAppDao().getBlockedApp(pkg)
            db.blockedAppDao().insert(existing?.copy(isInAllowlist = true) ?: BlockedApp(pkg, AppUtils.getAppName(app, pkg), isBlocked = false, isInAllowlist = true))
        }
        // Existing whitelist entries are kept; removing those requires the legacy settings editor.
        val preserved = db.essentialAppWhitelistDao().getWhitelistedPackageNames()
        mutable.update { it.copy(essential = required + packages + preserved) }
    }
    private suspend fun refreshUsage(now: Long) {
        if (!PermissionUtils.hasUsageStatsPermission(app)) { mutable.update { it.copy(usage = null, yesterday = null) }; return }
        val c = Calendar.getInstance().apply { timeInMillis = now }
        val priorNow = (c.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }.timeInMillis
        c.set(Calendar.HOUR_OF_DAY, 0); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        val midnight = c.timeInMillis
        c.add(Calendar.DAY_OF_YEAR, -1)
        val today = UsageWindowReader.read(app, midnight, now)
        val yesterday = UsageWindowReader.read(app, c.timeInMillis, priorNow)
        mutable.update { it.copy(usage = today, yesterday = yesterday) }
    }
}
