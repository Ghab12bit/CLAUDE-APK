package com.focusblock.app.blocking

import com.focusblock.app.database.FocusBlockDatabase
import com.focusblock.app.database.entity.Schedule
import java.util.Calendar

/** Read-through adapter for rules created by the earlier v13–15 redesign branch.
 * The original rows, notes, counters and lock hashes are retained unchanged. Enforcement of these
 * rules goes through [com.focusblock.app.core.PolicyRepository] and the policy engine. */
object ImportedRuleStore {
    data class Rule(val id: Long, val name: String, val packages: String, val enabled: Boolean,
        val manual: Boolean, val manualActive: Boolean, val until: Long?, val timed: Boolean,
        val start: Int, val end: Int, val days: String, val usage: Boolean, val minutes: Int,
        val hourly: Boolean, val launches: Boolean, val launchLimit: Int, val launchHourly: Boolean,
        val commitment: String, val supportsLaunch: Boolean = true) {
        fun inWindow(now: Long): Boolean {
            if (manual) return manualActive && (until == null || now < until)
            if (!timed) return true
            val c = Calendar.getInstance().apply { timeInMillis = now }
            if (start == end) return ((c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1).toString() in days.split(',')
            return SchedulePolicy.isActive(Schedule(name = name, startTimeMinutes = start, endTimeMinutes = end, daysOfWeek = days), c.get(Calendar.HOUR_OF_DAY) * 60 + c.get(Calendar.MINUTE), (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 + 1)
        }
        fun description(): String = buildString {
            if (manual) append(if (until == null) "Until stopped" else "Timed block")
            if (timed) append("Time window · ")
            if (usage) append("$minutes min/${if (hourly) "hour" else "day"} combined · ")
            if (launches) append("$launchLimit opens/${if (launchHourly) "hour" else "day"}")
        }.trim().trimEnd('·')
    }
    private fun exists(db: FocusBlockDatabase, table: String): Boolean = db.openHelper.readableDatabase.query(
        "SELECT name FROM sqlite_master WHERE type='table' AND name=?", arrayOf(table)).use { it.moveToFirst() }
    fun rules(db: FocusBlockDatabase): List<Rule> {
        if (!exists(db, "block_rules")) return emptyList()
        return db.openHelper.readableDatabase.query("SELECT * FROM block_rules").use { c -> buildList {
            fun text(n: String, fallback: String = ""): String { val i = c.getColumnIndex(n); return if (i >= 0 && !c.isNull(i)) c.getString(i) else fallback }
            fun number(n: String) = text(n, "0").toLongOrNull() ?: 0
            while (c.moveToNext()) add(Rule(number("id"), text("name"), text("packages"), number("isEnabled") == 1L,
                text("kind") == "MANUAL", number("isManualActive") == 1L, number("activeUntil").takeIf { it > 0 },
                number("hasTimeCondition") == 1L, number("startMinute").toInt(), number("endMinute").toInt(), text("daysOfWeek"),
                number("hasUsageCondition") == 1L, number("usageLimitMinutes").toInt(), text("usageWindow") == "HOURLY",
                number("hasLaunchCondition") == 1L, number("launchLimit").toInt(), text("launchWindow") == "HOURLY", text("commitment"), c.getColumnIndex("hasLaunchCondition") >= 0))
        } }
    }
    fun configurationLocked(db: FocusBlockDatabase): Boolean {
        if (!exists(db, "protection_lock")) return false
        return db.openHelper.readableDatabase.query("SELECT lockedUntil, level FROM protection_lock WHERE id=1").use { c ->
            c.moveToFirst() && c.getLong(0) > System.currentTimeMillis() && c.getString(1) != "OFF"
        }
    }
    fun save(db: FocusBlockDatabase, rule: Rule) {
        check(!configurationLocked(db)) { "Your existing configuration lock is still active." }
        val old = rules(db).firstOrNull { it.id == rule.id } ?: error("This rule no longer exists.")
        check(!(old.enabled && old.commitment != "OFF" && old.inWindow(System.currentTimeMillis()))) { "This rule is locked while active." }
        require(rule.name.isNotBlank() && QuickBlockPolicy.packages(rule.packages).isNotEmpty()) { "Name the rule and choose apps." }
        require(!rule.timed || (rule.start in 0..1439 && rule.end in 0..1439 && rule.days.split(',').mapNotNull(String::toIntOrNull).any { it in 1..7 })) { "Check times and repeat days." }
        require(!rule.usage || rule.minutes in 1..720) { "Choose 1–720 usage minutes." }
        require(!rule.launches || rule.launchLimit in 1..1000) { "Choose 1–1000 app opens." }
        val sqlite = db.openHelper.writableDatabase
        val columns = sqlite.query("PRAGMA table_info(block_rules)").use { c -> buildSet { while(c.moveToNext()) add(c.getString(c.getColumnIndexOrThrow("name"))) } }
        val values = linkedMapOf<String, Any>("name" to rule.name, "packages" to rule.packages,
            "hasTimeCondition" to (if (rule.timed) 1 else 0), "startMinute" to rule.start, "endMinute" to rule.end,
            "daysOfWeek" to rule.days, "hasUsageCondition" to (if (rule.usage) 1 else 0),
            "usageLimitMinutes" to rule.minutes, "usageWindow" to (if (rule.hourly) "HOURLY" else "DAILY"),
            "hasLaunchCondition" to (if (rule.launches) 1 else 0), "launchLimit" to rule.launchLimit,
            "launchWindow" to (if (rule.launchHourly) "HOURLY" else "DAILY"), "updatedAt" to System.currentTimeMillis())
            .filterKeys { it in columns }
        sqlite.execSQL("UPDATE block_rules SET ${values.keys.joinToString(",") { "$it=?" }} WHERE id=?", (values.values + rule.id).toTypedArray())
    }
    fun toggle(db: FocusBlockDatabase, rule: Rule) {
        check(!configurationLocked(db)) { "Your existing configuration lock is still active." }
        check(!(rule.enabled && rule.commitment != "OFF" && rule.inWindow(System.currentTimeMillis()))) { "This imported rule is locked while active." }
        db.openHelper.writableDatabase.execSQL("UPDATE block_rules SET isEnabled=?, updatedAt=? WHERE id=?", arrayOf(if (rule.enabled) 0 else 1, System.currentTimeMillis(), rule.id))
    }
}
