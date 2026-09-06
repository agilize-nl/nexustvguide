package com.nexustvguide.app.core.refresh

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StaleSemanticsTest {

    private val twoHoursMs = 2 * 60 * 60 * 1000L
    private val eightHoursMs = 8 * 60 * 60 * 1000L

    @Test
    fun `today and yesterday and older are stale after exactly 2 hours plus 1ms`() {
        val today = "2026-08-30"
        val nowMs = 100_000_000L

        // Today: 2 hours ago exactly is not yet stale (strict >)
        assertFalse(StaleChecker.isStale(nowMs - twoHoursMs, today, nowMs, today))
        // 2 hours + 1 ms is stale
        assertTrue(StaleChecker.isStale(nowMs - (twoHoursMs + 1), today, nowMs, today))

        // Yesterday ("2026-08-29")
        assertFalse(StaleChecker.isStale(nowMs - twoHoursMs, "2026-08-29", nowMs, today))
        assertTrue(StaleChecker.isStale(nowMs - (twoHoursMs + 1), "2026-08-29", nowMs, today))

        // Two days ago ("2026-08-28")
        assertFalse(StaleChecker.isStale(nowMs - twoHoursMs, "2026-08-28", nowMs, today))
        assertTrue(StaleChecker.isStale(nowMs - (twoHoursMs + 1), "2026-08-28", nowMs, today))
    }

    @Test
    fun `tomorrow is stale after 2 hours`() {
        val today = "2026-08-30"
        val tomorrow = "2026-08-31"
        val nowMs = 100_000_000L

        assertFalse(StaleChecker.isStale(nowMs - twoHoursMs, tomorrow, nowMs, today))
        assertTrue(StaleChecker.isStale(nowMs - (twoHoursMs + 1), tomorrow, nowMs, today))
    }

    @Test
    fun `day after tomorrow and later are stale after 8 hours`() {
        val today = "2026-08-30"
        val dayAfterTomorrow = "2026-09-01"
        val nowMs = 100_000_000L

        // 2 hours is NOT stale for day after tomorrow
        assertFalse(StaleChecker.isStale(nowMs - (twoHoursMs + 10_000), dayAfterTomorrow, nowMs, today))
        // 8 hours exactly is not yet stale
        assertFalse(StaleChecker.isStale(nowMs - eightHoursMs, dayAfterTomorrow, nowMs, today))
        // 8 hours + 1 ms is stale
        assertTrue(StaleChecker.isStale(nowMs - (eightHoursMs + 1), dayAfterTomorrow, nowMs, today))
    }
}
