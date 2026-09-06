package com.nexustvguide.app.core.time

import org.junit.Assert.assertEquals
import org.junit.Test
import org.threeten.bp.Instant

class GuideTimeTest {

    @Test
    fun `standard day UTC window is 24 hours`() {
        val (from, to) = GuideTime.getLocalDayUtcWindow("2026-08-30")
        // Amsterdam is in CEST (UTC+2) in August
        assertEquals("2026-08-29T22:00:00Z", from.toString())
        assertEquals("2026-08-30T22:00:00Z", to.toString())
        val durationHours = (to.toEpochMilli() - from.toEpochMilli()) / (1000 * 60 * 60)
        assertEquals(24L, durationHours)
    }

    @Test
    fun `DST transition in March produces 23-hour day`() {
        // In 2026, DST starts on Sunday March 29 (CET UTC+1 -> CEST UTC+2 at 02:00)
        val (from, to) = GuideTime.getLocalDayUtcWindow("2026-03-29")
        assertEquals("2026-03-28T23:00:00Z", from.toString())
        assertEquals("2026-03-29T22:00:00Z", to.toString())
        val durationHours = (to.toEpochMilli() - from.toEpochMilli()) / (1000 * 60 * 60)
        assertEquals(23L, durationHours)
    }

    @Test
    fun `DST transition in October produces 25-hour day`() {
        // In 2026, DST ends on Sunday October 25 (CEST UTC+2 -> CET UTC+1 at 03:00)
        val (from, to) = GuideTime.getLocalDayUtcWindow("2026-10-25")
        assertEquals("2026-10-24T22:00:00Z", from.toString())
        assertEquals("2026-10-25T23:00:00Z", to.toString())
        val durationHours = (to.toEpochMilli() - from.toEpochMilli()) / (1000 * 60 * 60)
        assertEquals(25L, durationHours)
    }

    @Test
    fun `getAmsterdamDateString converts UTC instant correctly across midnight`() {
        // 2026-08-30 23:30 UTC is 2026-08-31 01:30 CEST in Amsterdam
        val instant = Instant.parse("2026-08-30T23:30:00Z")
        val amsterdamDate = GuideTime.getAmsterdamDateString(instant)
        assertEquals("2026-08-31", amsterdamDate)
    }

    @Test
    fun `getTodayAmsterdam respects injected clock`() {
        val fixedInstant = Instant.parse("2026-12-31T23:30:00Z") // 00:30 on 2027-01-01 in Amsterdam
        val today = GuideTime.getTodayAmsterdam { fixedInstant }
        assertEquals("2027-01-01", today)
    }

    @Test
    fun `addDays and date ranges work as expected`() {
        assertEquals("2026-09-01", GuideTime.addDays("2026-08-31", 1))
        assertEquals("2026-08-30", GuideTime.addDays("2026-08-31", -1))

        val range = GuideTime.getLocalDateRange("2026-08-30", "2026-09-01")
        assertEquals(listOf("2026-08-30", "2026-08-31", "2026-09-01"), range)
    }
}
