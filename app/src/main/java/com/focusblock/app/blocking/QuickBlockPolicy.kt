package com.focusblock.app.blocking

/** Pure session rules shared by UI and both enforcement services. */
object QuickBlockPolicy {
    fun packages(csv: String): Set<String> = csv
        .split(",")
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()

    fun sanitizePackages(packages: List<String>): List<String> = packages
        .map(String::trim)
        .filter(String::isNotEmpty)
        .distinct()

    fun isExpired(endTime: Long?, now: Long): Boolean = endTime != null && now >= endTime

    fun packagesToRelease(blockedPackages: String, previouslyBlockedPackages: String): Set<String> =
        packages(blockedPackages) - packages(previouslyBlockedPackages)
}
