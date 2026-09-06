package com.nexustvguide.app.core.refresh

import com.nexustvguide.app.core.time.GuideTime

object StaleChecker {
    const val NEAR_DAYS_MAX_AGE_MS = 2 * 60 * 60 * 1000L  // 2 uur (7.200.000 ms)
    const val FAR_DAYS_MAX_AGE_MS = 8 * 60 * 60 * 1000L   // 8 uur (28.800.000 ms)

    /**
     * Gisteren t/m morgen: stale na 2 uur.
     * Overige dagen: stale na 8 uur.
     * Strikte ongelijkheid: ageMs > maxAgeMs.
     */
    fun isStale(publishedAtMs: Long, dateStr: String, nowMs: Long, todayStr: String): Boolean {
        val ageMs = nowMs - publishedAtMs
        val tomorrowStr = GuideTime.addDays(todayStr, 1)

        val maxAgeMs = if (dateStr <= todayStr || dateStr == tomorrowStr) {
            NEAR_DAYS_MAX_AGE_MS
        } else {
            FAR_DAYS_MAX_AGE_MS
        }

        return ageMs > maxAgeMs
    }
}
