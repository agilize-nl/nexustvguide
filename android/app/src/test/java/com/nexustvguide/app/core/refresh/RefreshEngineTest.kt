package com.nexustvguide.app.core.refresh

import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.core.domain.DaySnapshot
import com.nexustvguide.app.core.domain.Programme
import com.nexustvguide.app.core.nlziet.NlzietEpgClient
import com.nexustvguide.app.core.nlziet.NlzietEpgMatcher
import com.nexustvguide.app.core.nlziet.NlzietEpgResponse
import com.nexustvguide.app.core.source.tvgids.TvgidsClient
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Instant
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody

class RefreshEngineTest {

    private class FakeSnapshotStorage : SnapshotStorage {
        val snapshots = mutableMapOf<String, DaySnapshot>()
        var cleanedThreshold: String? = null

        override suspend fun getExistingProgrammeCount(date: String): Int? {
            return snapshots[date]?.programmes?.size
        }

        override suspend fun getSnapshot(date: String): DaySnapshot? {
            return snapshots[date]
        }

        override suspend fun saveDaySnapshot(snapshot: DaySnapshot) {
            snapshots[snapshot.date] = snapshot
        }

        override suspend fun cleanOldSnapshots(minAllowedDate: String) {
            cleanedThreshold = minAllowedDate
            snapshots.keys.removeAll { it < minAllowedDate }
        }
    }

    private val fixedNow = Instant.parse("2026-08-30T10:00:00Z") // 12:00 in Amsterdam
    private val channelNpo1 = Channel("npo1", "1", "NPO 1", inNlziet = true, nlzietChannelId = "npo1", sortOrder = 1)
    private val channelRtl4 = Channel("rtl4", "4", "RTL 4", inNlziet = true, nlzietChannelId = "rtl4", sortOrder = 4)

    @Test
    fun `preserves existing snapshot when near day drops programme count by more than 40 percent`() = runBlocking {
        val storage = FakeSnapshotStorage()
        val date = "2026-08-30"

        // Existing snapshot with 100 programmes
        val existingProgs = (1..100).map { id ->
            Programme(id = id.toString(), channelId = "npo1", title = "P $id", start = "2026-08-30T06:00:00Z", end = "2026-08-30T07:00:00Z")
        }
        storage.saveDaySnapshot(
            DaySnapshot(
                date = date,
                timeZone = "Europe/Amsterdam",
                from = "2026-08-29T22:00:00Z",
                to = "2026-08-30T22:00:00Z",
                sourceFetchedAt = fixedNow.toString(),
                publishedAt = fixedNow.toString(),
                channels = listOf(channelNpo1),
                programmes = existingProgs
            )
        )

        // Upstream returns only 50 programmes (50% drop, > 40%)
        val tvgidsJson = """
        {
          "version": "1.0",
          "data": {
            "1": {
              "ch_id": "1",
              "prog": [
                ${(1..50).joinToString(",") { id ->
                    """{"s": "1788079200", "e": "1788082800", "db_id": "$id", "title": "Prog $id"}"""
                }}
              ]
            }
          }
        }
        """.trimIndent()

        // Mock client returning this payload for day 0
        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(tvgidsJson.toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }.build()

        val tvgidsClient = TvgidsClient(com.nexustvguide.app.core.source.tvgids.TvgidsClientOptions(okHttpClient = okHttpClient))
        val epgClient = NlzietEpgClient(com.nexustvguide.app.core.nlziet.NlzietEpgClientOptions(okHttpClient = okHttpClient))

        val loggedMessages = mutableListOf<String>()
        val engine = RefreshEngine(
            tvgidsClient = tvgidsClient,
            epgClient = epgClient,
            epgMatcher = NlzietEpgMatcher,
            storage = storage,
            nowFn = { fixedNow },
            logger = { loggedMessages.add(it) }
        )

        val result = engine.refreshAll(listOf(channelNpo1))

        assertTrue(result.rejectedDays.contains(date))
        // Existing 100 programmes preserved!
        assertEquals(100, storage.getSnapshot(date)?.programmes?.size)
        assertTrue(loggedMessages.any { it.contains(">40% drop") })
    }

    @Test
    fun `purges snapshots older than today minus 3 days`() = runBlocking {
        val storage = FakeSnapshotStorage()
        val oldDate = "2026-08-26"
        storage.saveDaySnapshot(
            DaySnapshot(
                date = oldDate,
                timeZone = "Europe/Amsterdam",
                from = "2026-08-25T22:00:00Z",
                to = "2026-08-26T22:00:00Z",
                sourceFetchedAt = fixedNow.toString(),
                publishedAt = fixedNow.toString(),
                channels = listOf(channelNpo1),
                programmes = emptyList()
            )
        )

        val tvgidsJson = """
        {
          "version": "1.0",
          "data": {
            "1": {
              "ch_id": "1",
              "prog": [
                {"s": "1788079200", "e": "1788082800", "db_id": "1", "title": "Prog 1"}
              ]
            }
          }
        }
        """.trimIndent()

        val okHttpClient = okhttp3.OkHttpClient.Builder()
            .addInterceptor { chain ->
                okhttp3.Response.Builder()
                    .request(chain.request())
                    .protocol(okhttp3.Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(tvgidsJson.toResponseBody("application/json".toMediaTypeOrNull()))
                    .build()
            }.build()

        val tvgidsClient = TvgidsClient(com.nexustvguide.app.core.source.tvgids.TvgidsClientOptions(okHttpClient = okHttpClient))
        val epgClient = NlzietEpgClient(com.nexustvguide.app.core.nlziet.NlzietEpgClientOptions(okHttpClient = okHttpClient))

        val engine = RefreshEngine(
            tvgidsClient = tvgidsClient,
            epgClient = epgClient,
            epgMatcher = NlzietEpgMatcher,
            storage = storage,
            nowFn = { fixedNow }
        )

        engine.refreshAll(listOf(channelNpo1))

        // Today is 2026-08-30, minus 3 days is 2026-08-27. Old date 2026-08-26 should be cleaned!
        assertEquals("2026-08-27", storage.cleanedThreshold)
        assertTrue(!storage.snapshots.containsKey(oldDate))
    }
}
