package com.focusblock.app.core

import com.focusblock.app.R
import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.AppLimitEntity
import com.focusblock.app.database.entity.RecommendationEntity
import com.focusblock.app.database.entity.Schedule
import com.focusblock.app.database.entity.UnlockEventEntity
import com.focusblock.app.policy.OverrideKind
import com.focusblock.app.policy.PolicyTime
import com.focusblock.app.policy.ReasonType
import com.focusblock.app.policy.RecommendationEngine
import com.focusblock.app.policy.RecommendationEngine.Proposal
import com.focusblock.app.policy.TimeWindow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * One suggestion at a time (spec 8.6), from logged data only. Nothing changes until [apply] is
 * called from an explicit tap; [dismiss] hides the suggestion for seven days. Every suggestion's
 * status (shown / applied / dismissed) is logged in the recommendations table.
 */
class RecommendationRepository(
    private val context: android.content.Context,
    private val db: FocusBlockDatabase,
    private val clock: AppClock,
    private val usage: UsageRepository,
    private val policy: PolicyRepository,
    private val apps: InstalledApps,
) {
    suspend fun current(): RecommendationEngine.Recommendation? = withContext(Dispatchers.IO) {
        val now = clock.now()
        val zone = clock.zone()
        val since = now - RecommendationEngine.LOOKBACK_DAYS * DAY
        val attempts = db.attemptDao().since(since).map { RecommendationEngine.Attempt(it.timestamp, it.packageName) }
        val unlocks = db.unlockEventDao().since(since).filter { it.status == UnlockEventEntity.GRANTED }.map {
            RecommendationEngine.Unlock(
                it.grantedAt ?: it.requestedAt, it.packageName,
                if (it.kind == OverrideKind.EMERGENCY.name) OverrideKind.EMERGENCY else OverrideKind.OPEN_ANYWAY,
                ReasonType.values().firstOrNull { r -> r.name == it.reasonType }, it.ruleId,
            )
        }
        val today = PolicyTime.localDate(now, zone)
        val usageByDay = usage.dailyTotals(7)?.filterKeys { it.isBefore(today) }.orEmpty()
        val firstDay = listOfNotNull(
            db.attemptDao().firstTimestamp()?.let { PolicyTime.localDate(it, zone) },
            db.blockSessionDao().firstStartedAt()?.let { PolicyTime.localDate(it, zone) },
            usage.firstDataDay(14),
        ).minOrNull()
        val snap = policy.snapshot()
        val records = db.recommendationDao().all()
        val hidden = records.associate { r ->
            r.signature to when (r.status) {
                RecommendationEntity.APPLIED -> Long.MAX_VALUE
                RecommendationEntity.DISMISSED -> r.snoozedUntil
                else -> 0L
            }
        }
        val rec = RecommendationEngine.recommend(
            RecommendationEngine.Input(now, zone, firstDay, attempts, unlocks, usageByDay, snap.routines, snap.bedtime, snap.appLimits, snap.exempt, hidden),
        ) ?: return@withContext null
        val existing = records.firstOrNull { it.signature == rec.signature }
        db.recommendationDao().upsert(
            existing?.copy(status = RecommendationEntity.SHOWN, lastShownAt = now)
                ?: RecommendationEntity(rec.signature, kind(rec.proposal), payload(rec), RecommendationEntity.SHOWN, now, now),
        )
        rec
    }

    /** Creates or changes a rule. Called only from the Apply button. */
    suspend fun apply(rec: RecommendationEngine.Recommendation) = withContext(Dispatchers.IO) {
        when (val p = rec.proposal) {
            is Proposal.AddRoutine -> db.scheduleDao().insert(
                Schedule(name = context.getString(R.string.sugg_routine_name), startTimeMinutes = p.startMinute, endTimeMinutes = p.endMinute,
                    daysOfWeek = TimeWindow.formatDays(p.days), blockedPackages = p.packages.joinToString(",")),
            )
            is Proposal.AddAppLimit -> db.appLimitDao().upsert(AppLimitEntity(name = apps.label(p.pkg), packages = p.pkg, minutesPerDay = p.minutes))
            is Proposal.MakeRoutineStrict -> db.scheduleDao().getSchedule(p.routineId)?.let { db.scheduleDao().update(it.copy(isStrictMode = true)) }
        }
        record(rec, RecommendationEntity.APPLIED)
    }

    suspend fun dismiss(rec: RecommendationEngine.Recommendation) = record(rec, RecommendationEntity.DISMISSED)

    suspend fun record(rec: RecommendationEngine.Recommendation, status: String) = withContext(Dispatchers.IO) {
        val now = clock.now()
        val existing = db.recommendationDao().get(rec.signature)
        val snooze = if (status == RecommendationEntity.DISMISSED) now + RecommendationEngine.SNOOZE_DAYS * DAY else existing?.snoozedUntil ?: 0
        db.recommendationDao().upsert(
            (existing ?: RecommendationEntity(rec.signature, kind(rec.proposal), payload(rec), status, now, now))
                .copy(status = status, actedAt = now, snoozedUntil = snooze),
        )
    }

    private fun kind(p: Proposal) = when (p) {
        is Proposal.AddRoutine -> "ADD_ROUTINE"
        is Proposal.AddAppLimit -> "ADD_APP_LIMIT"
        is Proposal.MakeRoutineStrict -> "MAKE_STRICT"
    }

    private fun payload(rec: RecommendationEngine.Recommendation): String {
        val j = JSONObject().put("days", rec.evidence.days).put("count", rec.evidence.count).put("typical", rec.evidence.typicalMinutes)
        when (val p = rec.proposal) {
            is Proposal.AddRoutine -> j.put("start", p.startMinute).put("end", p.endMinute).put("weekdays", TimeWindow.formatDays(p.days)).put("apps", p.packages.joinToString(","))
            is Proposal.AddAppLimit -> j.put("app", p.pkg).put("minutes", p.minutes)
            is Proposal.MakeRoutineStrict -> j.put("routine", p.routineId)
        }
        return j.toString()
    }

    companion object { private const val DAY = 24 * 3_600_000L }
}
