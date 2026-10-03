package com.focusblock.app.policy

/**
 * "Suggested from your usage": the apps worth blocking, ranked from the last days of use. It is
 * recomputed whenever it is shown, so the list follows what you actually use (formulas in
 * docs/metrics.md).
 */
object SmartApps {
    data class Suggestion(
        val pkg: String,
        /** Average foreground time per day over days with data. */
        val dailyAverage: Long,
        /** Blocked openings of this app in the same days. */
        val attempts: Int,
        val category: AppCategory,
    )

    /** Below this an app is not worth suggesting, unless it was blocked often. */
    const val MIN_DAILY_MS = 10 * 60_000L
    const val MIN_ATTEMPTS = 3

    /**
     * Ranks apps in [days]. Productive apps are never suggested; neutral apps count for 60% of their
     * time; every blocked attempt adds five minutes, because it shows a pull towards the app.
     * [eligible] removes essential, safety and uncounted apps.
     */
    fun rank(
        days: List<DayUsage>,
        categoryOf: (String) -> AppCategory,
        eligible: (String) -> Boolean,
        attempts: Map<String, Int> = emptyMap(),
        limit: Int = 8,
    ): List<Suggestion> {
        val withData = days.count { d -> d.totals.values.any { it > 0 } }.coerceAtLeast(1)
        val totals = HashMap<String, Long>()
        days.forEach { d -> d.totals.forEach { (pkg, ms) -> totals[pkg] = (totals[pkg] ?: 0L) + ms } }
        val candidates = (totals.keys + attempts.keys).filter(eligible)
        return candidates.mapNotNull { pkg ->
            val category = categoryOf(pkg)
            if (category == AppCategory.PRODUCTIVE) return@mapNotNull null
            val avg = (totals[pkg] ?: 0L) / withData
            val tries = attempts[pkg] ?: 0
            if (avg < MIN_DAILY_MS && tries < MIN_ATTEMPTS) return@mapNotNull null
            val weight = if (category == AppCategory.DISTRACTING) 1.0 else 0.6
            val score = avg * weight + tries * 5 * 60_000.0
            Suggestion(pkg, avg, tries, category) to score
        }.sortedByDescending { it.second }.take(limit).map { it.first }
    }
}
