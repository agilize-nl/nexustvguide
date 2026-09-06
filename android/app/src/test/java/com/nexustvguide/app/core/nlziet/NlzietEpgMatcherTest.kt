package com.nexustvguide.app.core.nlziet

import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.core.domain.Programme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class NlzietEpgMatcherTest {

    private val channels = listOf(
        Channel(id = "npo1", sourceId = "1", name = "NPO 1", logoUrl = null, inNlziet = true, nlzietSlug = "npo-1", nlzietChannelId = "npo1", sortOrder = 1),
        Channel(id = "vrtcanvas", sourceId = "6", name = "VRT Canvas", logoUrl = null, inNlziet = true, nlzietSlug = "vrt-canvas", nlzietChannelId = "canvas", sortOrder = 14),
        Channel(id = "discovery", sourceId = "29", name = "Discovery", logoUrl = null, inNlziet = false, nlzietSlug = null, nlzietChannelId = null, sortOrder = 21)
    )

    private fun createProg(title: String, startIso: String, endIso: String, channelId: String = "npo1"): Programme {
        return Programme(
            id = "1001",
            channelId = channelId,
            title = title,
            start = startIso,
            end = endIso
        )
    }

    private fun createEpgContent(
        title: String,
        startIso: String,
        endIso: String,
        isReplay: Boolean = true,
        isRestart: Boolean = true
    ): NlzietEpgContent {
        return NlzietEpgContent(
            contentItemId = "pXZD1nmyCkSuW_pB1ylCQg",
            assetId = "108C33FB3A16FDFCE5E88B43871AC6BA",
            title = title,
            startAt = startIso,
            endAt = endIso,
            isReplayAllowed = isReplay,
            isRestartAllowed = isRestart
        )
    }

    @Test
    fun `normalizes titles correctly`() {
        assertEquals("journaal", NlzietEpgMatcher.normalizeEpgTitle("NOS Journaal"))
        assertEquals("radar", NlzietEpgMatcher.normalizeEpgTitle("AVROTROS: Radar"))
        assertEquals("b b vol liefde", NlzietEpgMatcher.normalizeEpgTitle("B&B Vol Liefde - Afl. 34"))
        assertEquals("studio sport live wk roeien", NlzietEpgMatcher.normalizeEpgTitle("Studio Sport Live: WK Roeien"))
        assertEquals("creme de la creme", NlzietEpgMatcher.normalizeEpgTitle("Crème de la crème"))
    }

    @Test
    fun `title normalization is safe under Turkish default locale`() {
        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale("tr", "TR"))
            assertEquals("info", NlzietEpgMatcher.normalizeEpgTitle("INFO"))
            assertEquals("journaal", NlzietEpgMatcher.normalizeEpgTitle("NOS Journaal"))
        } finally {
            Locale.setDefault(originalLocale)
        }
    }

    @Test
    fun `matches exact title and timing within 6 minutes start and 10 minutes duration tolerance`() {
        val prog = createProg("NOS Journaal", "2026-08-30T18:00:00.000Z", "2026-08-30T18:30:00.000Z")
        // EPG start is 2 min later (18:02 UTC = 20:02 +02:00) en duration is 28 min ipv 30 min (2 min diff)
        val epgItems = listOf(
            createEpgContent("Journaal", "2026-08-30T20:02:00+02:00", "2026-08-30T20:30:00+02:00")
        )

        val result = NlzietEpgMatcher.matchProgramme(prog, epgItems, "npo1")
        assertNotNull(result.target)
        assertEquals("pXZD1nmyCkSuW_pB1ylCQg", result.target?.contentItemId)
        assertEquals("108C33FB3A16FDFCE5E88B43871AC6BA", result.target?.assetId)
        assertEquals("npo1", result.target?.channelId)
        assertTrue(result.target!!.isReplayAllowed)
        assertFalse(result.isAmbiguous)
        assertFalse(result.isTimingOrTitleMismatch)
    }

    @Test
    fun `rejects match when start time difference is greater than 6 minutes`() {
        val prog = createProg("NOS Journaal", "2026-08-30T18:00:00.000Z", "2026-08-30T18:30:00.000Z")
        // EPG start is 7 min later (20:07 +02:00)
        val epgItems = listOf(
            createEpgContent("Journaal", "2026-08-30T20:07:00+02:00", "2026-08-30T20:37:00+02:00")
        )

        val result = NlzietEpgMatcher.matchProgramme(prog, epgItems, "npo1")
        assertNull(result.target)
        assertTrue(result.isTimingOrTitleMismatch)
        assertFalse(result.isAmbiguous)
    }

    @Test
    fun `rejects match when duration difference is greater than 10 minutes`() {
        val prog = createProg("NOS Journaal", "2026-08-30T18:00:00.000Z", "2026-08-30T18:30:00.000Z")
        // Start is equal, duration is 45 min instead of 30 min (15 min diff)
        val epgItems = listOf(
            createEpgContent("Journaal", "2026-08-30T20:00:00+02:00", "2026-08-30T20:45:00+02:00")
        )

        val result = NlzietEpgMatcher.matchProgramme(prog, epgItems, "npo1")
        assertNull(result.target)
        assertTrue(result.isTimingOrTitleMismatch)
    }

    @Test
    fun `rejects ambiguous candidates when multiple items match timing and title`() {
        val prog = createProg("Zin in Zappelin", "2026-08-30T07:15:00.000Z", "2026-08-30T07:20:00.000Z")
        val epgItems = listOf(
            createEpgContent("Zin in Zappelin", "2026-08-30T09:14:00+02:00", "2026-08-30T09:19:00+02:00"),
            NlzietEpgContent(
                contentItemId = "a2ZDam4e8UKeXODFs9nJjA",
                assetId = "921260CF2892B3BD8A3B0BBAB40B4B5B",
                title = "Zin in Zappelin",
                startAt = "2026-08-30T09:18:00+02:00",
                endAt = "2026-08-30T09:23:00+02:00",
                isReplayAllowed = true,
                isRestartAllowed = true
            )
        )

        val result = NlzietEpgMatcher.matchProgramme(prog, epgItems, "npo1")
        assertNull(result.target)
        assertTrue(result.isAmbiguous)
        assertFalse(result.isTimingOrTitleMismatch)
    }

    @Test
    fun `enriches a list of programmes and calculates enrichment statistics`() {
        val progs = listOf(
            createProg("NOS Journaal", "2026-08-30T18:00:00.000Z", "2026-08-30T18:30:00.000Z", "npo1"),
            createProg("Terzake", "2026-08-30T18:00:00.000Z", "2026-08-30T18:35:00.000Z", "vrtcanvas"),
            createProg("Shark Week", "2026-08-30T18:00:00.000Z", "2026-08-30T19:00:00.000Z", "discovery")
        )

        val epgResponse = NlzietEpgResponse(
            data = listOf(
                NlzietEpgChannelGroup(
                    channelId = "npo1",
                    programLocations = listOf(
                        NlzietEpgProgramLocation(createEpgContent("Journaal", "2026-08-30T20:00:00+02:00", "2026-08-30T20:30:00+02:00"))
                    )
                ),
                NlzietEpgChannelGroup(
                    channelId = "canvas",
                    programLocations = listOf(
                        NlzietEpgProgramLocation(
                            NlzietEpgContent(
                                contentItemId = "canvasItem123456789012",
                                assetId = "AABBCCDDEEFF00112233445566778899",
                                title = "Terzake",
                                startAt = "2026-08-30T20:00:00+02:00",
                                endAt = "2026-08-30T20:35:00+02:00",
                                isReplayAllowed = false,
                                isRestartAllowed = true
                            )
                        )
                    )
                )
            )
        )

        val stats = NlzietEpgMatcher.enrichProgrammes(progs, epgResponse, channels)

        assertEquals(3, stats.totalProgrammes)
        assertEquals(2, stats.epgEligibleProgrammes) // discovery is not inNlziet
        assertEquals(2, stats.exactTargets)
        assertEquals(1, stats.replayAllowedTargets) // NPO 1 has isReplayAllowed: true, Canvas has false

        assertEquals("pXZD1nmyCkSuW_pB1ylCQg", progs[0].nlziet?.contentItemId)
        assertEquals("npo1", progs[0].nlziet?.channelId)
        assertNull(progs[0].nlzietId)

        assertEquals("canvasItem123456789012", progs[1].nlziet?.contentItemId)
        assertEquals("canvas", progs[1].nlziet?.channelId)
        assertFalse(progs[1].nlziet!!.isReplayAllowed)

        assertNull(progs[2].nlziet)
    }
}
