package com.nexustvguide.app.core.refresh

import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.core.domain.DaySnapshot
import com.nexustvguide.app.core.domain.NlzietProgrammeTarget
import com.nexustvguide.app.core.nlziet.EpgSource
import com.nexustvguide.app.core.nlziet.NlzietEpgMatcher
import com.nexustvguide.app.core.nlziet.NlzietEpgResponse
import com.nexustvguide.app.core.source.tvgids.ProgrammeSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Instant

/**
 * Plan §5.2: a programme spanning midnight is stored once per overlapping day, and the
 * two rows must be independent so enriching or replacing day A never mutates day B.
 */
class MidnightOverlapTest {

    private val fixedNow = Instant.parse("2026-08-30T10:00:00Z") // 12:00 Amsterdam
    private val channel = Channel("npo1", "1", "NPO 1", inNlziet = true, nlzietChannelId = "npo1", sortOrder = 1)

    private class RecordingStorage : SnapshotStorage {
        val snapshots = mutableMapOf<String, DaySnapshot>()
        override suspend fun getExistingProgrammeCount(date: String): Int? = snapshots[date]?.programmes?.size
        override suspend fun getSnapshot(date: String): DaySnapshot? = snapshots[date]
        override suspend fun saveDaySnapshot(snapshot: DaySnapshot) { snapshots[snapshot.date] = snapshot }
        override suspend fun cleanOldSnapshots(minAllowedDate: String) {
            snapshots.keys.removeAll { it < minAllowedDate }
        }
    }

    private object EmptyEpg : EpgSource {
        override fun isDateInEpgWindow(dateStr: String, todayStr: String?) = false
        override suspend fun fetchEpg(dateStr: String, channelIds: List<String>, todayStr: String?) =
            NlzietEpgResponse(emptyList())
    }

    /**
     * One programme runs 23:30–00:30 Amsterdam across the 30th/31st boundary
     * (21:30Z–22:30Z), so it overlaps both local days.
     */
    private object MidnightSource : ProgrammeSource {
        override suspend fun fetchPrograms(dayOffset: Int, channelSourceIds: List<String>?): String {
            val startEpoch = Instant.parse("2026-08-30T21:30:00Z").epochSecond
            val endEpoch = Instant.parse("2026-08-30T22:30:00Z").epochSecond
            return """{"version":"1.0","data":{"1":{"ch_id":"1","prog":[
                {"s":"$startEpoch","e":"$endEpoch","db_id":"555","title":"Late Night Show"}]}}}"""
        }
    }

    @Test
    fun `a midnight-spanning programme lands in both days as independent rows`() = runBlocking {
        val storage = RecordingStorage()
        RefreshEngine(
            tvgidsClient = MidnightSource,
            epgClient = EmptyEpg,
            epgMatcher = NlzietEpgMatcher,
            storage = storage,
            nowFn = { fixedNow }
        ).refreshAll(listOf(channel))

        val day30 = storage.snapshots["2026-08-30"]?.programmes.orEmpty()
        val day31 = storage.snapshots["2026-08-31"]?.programmes.orEmpty()

        assertEquals(1, day30.size)
        assertEquals(1, day31.size)
        assertEquals("555", day30[0].id)
        assertEquals("555", day31[0].id)

        // Independent instances: mutating one day's copy must not alias the other.
        assertNotSame(day30[0], day31[0])
        day30[0].nlziet = NlzietProgrammeTarget(
            kind = "replay",
            contentItemId = "pXZD1nmyCkSuW_pB1ylCQg",
            assetId = "108C33FB3A16FDFCE5E88B43871AC6BA",
            channelId = "npo1",
            isReplayAllowed = true,
            isRestartAllowed = true
        )
        assertTrue("day 31 must not be mutated by enriching day 30", day31[0].nlziet == null)
    }

    @Test
    fun `replacing one day preserves the neighbouring day`() = runBlocking {
        val storage = RecordingStorage()
        val engine = RefreshEngine(
            tvgidsClient = MidnightSource,
            epgClient = EmptyEpg,
            epgMatcher = NlzietEpgMatcher,
            storage = storage,
            nowFn = { fixedNow }
        )
        engine.refreshAll(listOf(channel))
        val before = storage.snapshots["2026-08-31"]?.programmes?.size

        // A second cycle rewrites every day; the neighbour keeps its own snapshot.
        engine.refreshAll(listOf(channel))

        assertEquals(before, storage.snapshots["2026-08-31"]?.programmes?.size)
        assertEquals(1, storage.snapshots["2026-08-30"]?.programmes?.size)
    }
}
