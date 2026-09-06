package com.nexustvguide.app.core.refresh

import com.nexustvguide.app.core.domain.DaySnapshot

interface SnapshotStorage {
    suspend fun getExistingProgrammeCount(date: String): Int?
    suspend fun getSnapshot(date: String): DaySnapshot?
    suspend fun saveDaySnapshot(snapshot: DaySnapshot)
    suspend fun cleanOldSnapshots(minAllowedDate: String)
}
