package com.nexustvguide.app.core.time

import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.ZoneId
import org.threeten.bp.format.DateTimeFormatter

object GuideTime {
    val ZONE: ZoneId = ZoneId.of("Europe/Amsterdam")
    const val TIME_ZONE = "Europe/Amsterdam"

    private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    fun getLocalDayUtcWindow(dateStr: String): Pair<Instant, Instant> {
        val localDate = LocalDate.parse(dateStr)
        val fromInstant = localDate.atStartOfDay(ZONE).toInstant()
        val toInstant = localDate.plusDays(1).atStartOfDay(ZONE).toInstant()
        return Pair(fromInstant, toInstant)
    }

    fun getAmsterdamDateString(instant: Instant): String {
        return instant.atZone(ZONE).toLocalDate().toString()
    }

    fun getTodayAmsterdam(clock: () -> Instant = { Instant.now() }): String {
        return clock().atZone(ZONE).toLocalDate().toString()
    }

    fun addDays(dateStr: String, days: Long): String {
        return LocalDate.parse(dateStr).plusDays(days).toString()
    }

    fun getLocalDateRange(fromStr: String, toStr: String): List<String> {
        var current = LocalDate.parse(fromStr)
        val end = LocalDate.parse(toStr)
        val result = mutableListOf<String>()
        while (!current.isAfter(end)) {
            result.add(current.toString())
            current = current.plusDays(1)
        }
        return result
    }

    fun formatUtcIso(instant: Instant): String {
        return ISO_FORMATTER.format(instant)
    }
}
