package com.nexustvguide.app.data.repository

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.nexustvguide.app.data.local.AppDatabase
import com.nexustvguide.app.data.local.ChannelEntity
import com.nexustvguide.app.data.local.DayMetaEntity
import com.nexustvguide.app.data.local.ProgrammeEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.threeten.bp.Instant

@RunWith(AndroidJUnit4::class)
class LocalGuideRepositoryTest {

    private lateinit var db: AppDatabase
    private lateinit var context: Context

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `observes guide for date from room database`() = runBlocking {
        val dao = db.guideDao()
        val repo = LocalGuideRepository(context, dao)

        val channel = ChannelEntity("npo1", "1", "NPO 1", null, true, "npo-1", "npo1", 1)
        dao.insertChannels(listOf(channel))

        val date = "2026-08-30"
        val startMs = Instant.parse("2026-08-30T10:00:00Z").toEpochMilli()
        val endMs = Instant.parse("2026-08-30T11:00:00Z").toEpochMilli()

        val meta = DayMetaEntity(
            date = date,
            fromUtcMs = Instant.parse("2026-08-29T22:00:00Z").toEpochMilli(),
            toUtcMs = Instant.parse("2026-08-30T22:00:00Z").toEpochMilli(),
            sourceFetchedAtMs = startMs,
            publishedAtMs = startMs,
            programmeCount = 1
        )
        val prog = ProgrammeEntity(
            date = date,
            id = "101",
            channelId = "npo1",
            title = "Test Show",
            startUtcMs = startMs,
            endUtcMs = endMs,
            description = "Desc",
            imageUrl = null,
            genre = null,
            isLive = false,
            isRerun = false,
            isPremiere = false,
            ageRating = "AL",
            nlzietKind = "replay",
            nlzietContentItemId = "pXZD1nmyCkSuW_pB1ylCQg",
            nlzietAssetId = "108C33FB3A16FDFCE5E88B43871AC6BA",
            nlzietChannelId = "npo1",
            nlzietReplayAllowed = true,
            nlzietRestartAllowed = true
        )

        dao.replaceDay(date, meta, listOf(prog))

        val emitted = repo.observeGuideForDate(date).first()
        assertNotNull(emitted)
        assertEquals(date, emitted!!.meta.date)
        assertEquals(1, emitted.channels.size)
        assertEquals(1, emitted.programmes.size)
        assertEquals("Test Show", emitted.programmes[0].title)
        assertEquals("pXZD1nmyCkSuW_pB1ylCQg", emitted.programmes[0].nlziet?.contentItemId)
    }
}
