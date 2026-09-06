package com.nexustvguide.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "day_meta")
data class DayMetaEntity(
    @PrimaryKey val date: String,  // "YYYY-MM-DD" in Europe/Amsterdam
    val fromUtcMs: Long,
    val toUtcMs: Long,
    val sourceFetchedAtMs: Long,
    val publishedAtMs: Long,
    val programmeCount: Int
)
