package com.nexustvguide.app.data.local

import androidx.room.Embedded
import androidx.room.Relation
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

data class StoredDay(
    @Embedded val meta: DayMetaEntity,
    @Relation(parentColumn = "date", entityColumn = "date") val programmes: List<ProgrammeEntity>
)

@Dao
interface GuideDao {

    @Transaction
    @Query("SELECT * FROM day_meta WHERE date = :date")
    suspend fun getDay(date: String): StoredDay?

    @Transaction
    @Query("SELECT * FROM day_meta WHERE date = :date")
    fun observeDay(date: String): Flow<StoredDay?>

    @Query("SELECT * FROM channels WHERE inNlziet = 1 ORDER BY sortOrder ASC")
    suspend fun getChannels(): List<ChannelEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertChannels(channels: List<ChannelEntity>)

    @Query("""
        SELECT p.* FROM programmes AS p
        JOIN channels AS c ON c.id = p.channelId
        WHERE p.date = :date
          AND p.startUtcMs < :dayEndMs AND p.endUtcMs > :dayStartMs
        ORDER BY c.sortOrder ASC, p.startUtcMs ASC, p.id ASC
    """)
    fun observeProgrammesForDate(date: String, dayStartMs: Long, dayEndMs: Long): Flow<List<ProgrammeEntity>>

    @Query("""
        SELECT p.* FROM programmes AS p
        JOIN channels AS c ON c.id = p.channelId
        WHERE p.date = :date
          AND p.startUtcMs < :dayEndMs AND p.endUtcMs > :dayStartMs
        ORDER BY c.sortOrder ASC, p.startUtcMs ASC, p.id ASC
    """)
    suspend fun getProgrammesForDate(date: String, dayStartMs: Long, dayEndMs: Long): List<ProgrammeEntity>

    @Query("SELECT * FROM day_meta WHERE date = :date")
    fun observeDayMeta(date: String): Flow<DayMetaEntity?>

    @Query("SELECT * FROM day_meta WHERE date = :date")
    suspend fun getDayMeta(date: String): DayMetaEntity?

    @Query("SELECT * FROM day_meta")
    suspend fun getAllDayMetas(): List<DayMetaEntity>

    @Query("DELETE FROM programmes WHERE date = :date")
    suspend fun deleteProgrammesForDate(date: String)

    @Query("DELETE FROM day_meta WHERE date = :date")
    suspend fun deleteDayMetaForDate(date: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProgrammes(programmes: List<ProgrammeEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertDayMeta(dayMeta: DayMetaEntity)

    @Transaction
    suspend fun replaceDay(date: String, dayMeta: DayMetaEntity, programmes: List<ProgrammeEntity>) {
        require(dayMeta.date == date && programmes.all { it.date == date })
        require(dayMeta.programmeCount == programmes.size)
        require(programmes.map { it.channelId to it.id }.distinct().size == programmes.size)
        deleteProgrammesForDate(date)
        deleteDayMetaForDate(date)
        insertProgrammes(programmes)
        insertDayMeta(dayMeta)
    }

    @Query("DELETE FROM programmes WHERE date < :minAllowedDate")
    suspend fun deleteOldProgrammes(minAllowedDate: String)

    @Query("DELETE FROM day_meta WHERE date < :minAllowedDate")
    suspend fun deleteOldDayMetas(minAllowedDate: String)

    @Transaction
    suspend fun cleanOldDays(minAllowedDate: String) {
        deleteOldProgrammes(minAllowedDate)
        deleteOldDayMetas(minAllowedDate)
    }
}
