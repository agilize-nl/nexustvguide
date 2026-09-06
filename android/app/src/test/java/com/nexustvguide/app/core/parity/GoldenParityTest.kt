package com.nexustvguide.app.core.parity

import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.core.nlziet.NlzietEpgContent
import com.nexustvguide.app.core.nlziet.NlzietEpgMatcher
import com.nexustvguide.app.core.source.tvgids.ProgrammeMapper
import com.nexustvguide.app.core.source.tvgids.RawProgramme
import com.nexustvguide.app.core.source.tvgids.TvgidsParser
import com.nexustvguide.app.core.source.tvgids.ValidationStats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GoldenParityTest {

    private fun findFixtureFile(fileName: String): File {
        val candidates = listOf(
            File("../../tvguide-api/test/fixtures/$fileName"),
            File("../tvguide-api/test/fixtures/$fileName"),
            File("tvguide-api/test/fixtures/$fileName")
        )
        return candidates.firstOrNull { it.exists() }
            ?: throw IllegalStateException("Could not find fixture file $fileName in candidates: $candidates")
    }

    @Test
    fun `golden test parses real upstream dictionary fixture identically to Node`() {
        val file = findFixtureFile("programs_fixture.json")
        val json = file.readText()

        val stats = ValidationStats()
        val parsed = TvgidsParser.parseProgramsEnvelope(json, stats)

        assertEquals(0, stats.skippedMalformedProgrammesCount)
        assertEquals(3, parsed.size)
        assertTrue(parsed.containsKey("1"))

        val progsChannel1 = parsed["1"]!!
        assertTrue(progsChannel1.isNotEmpty())

        // Map first programme
        val firstRaw = progsChannel1[0]
        val mapped = ProgrammeMapper.mapProgramme(firstRaw, "npo1")

        assertEquals(firstRaw.db_id, mapped.id)
        assertEquals("npo1", mapped.channelId)
        assertNotNull(mapped.title)
        assertNotNull(mapped.start)
        assertNotNull(mapped.end)
        assertTrue(mapped.start.endsWith("Z"))
        assertTrue(mapped.end.endsWith("Z"))
    }

    @Test
    fun `golden test parses real upstream array fixture identically to Node`() {
        val file = findFixtureFile("programs_array_fixture.json")
        val json = file.readText()

        val stats = ValidationStats()
        val parsed = TvgidsParser.parseProgramsEnvelope(json, stats)

        assertEquals(0, stats.skippedMalformedProgrammesCount)
        assertTrue(parsed.containsKey("1"))
        val progs = parsed["1"]!!
        assertEquals(1, progs.size)
        assertEquals("Nederland in beweging", progs[0].title)
        assertEquals("218748382", progs[0].db_id)

        val mapped = ProgrammeMapper.mapProgramme(progs[0], "npo1")
        assertEquals("Nederland in beweging", mapped.title)
        assertEquals("218748382", mapped.id)
        assertEquals("npo1", mapped.channelId)
        assertTrue(mapped.isRerun)
        assertFalse(mapped.isLive)
        assertEquals("6", mapped.ageRating)
        assertEquals("Gymnastiekprogramma", mapped.genre)
        assertEquals("Beweeg mee met 'Nederland in beweging' & blijf fit!", mapped.description)
    }

    @Test
    fun `golden test verifies exact EPG matcher target equivalence`() {
        val channel = Channel(id = "npo1", sourceId = "1", name = "NPO 1", inNlziet = true, nlzietChannelId = "npo1")
        val raw = RawProgramme(
            s = "1788112800", // 2026-08-30 18:00:00 UTC
            e = "1788114600", // 2026-08-30 18:30:00 UTC
            db_id = "218748999",
            title = "NOS Journaal"
        )
        val prog = ProgrammeMapper.mapProgramme(raw, "npo1")

        val epgItems = listOf(
            NlzietEpgContent(
                contentItemId = "pXZD1nmyCkSuW_pB1ylCQg",
                assetId = "108C33FB3A16FDFCE5E88B43871AC6BA",
                title = "Journaal",
                startAt = "2026-08-30T20:02:00+02:00",
                endAt = "2026-08-30T20:30:00+02:00",
                isReplayAllowed = true,
                isRestartAllowed = true
            )
        )

        val matchResult = NlzietEpgMatcher.matchProgramme(prog, epgItems, "npo1")
        assertNotNull(matchResult.target)
        val target = matchResult.target!!

        assertEquals("replay", target.kind)
        assertEquals("pXZD1nmyCkSuW_pB1ylCQg", target.contentItemId)
        assertEquals("108C33FB3A16FDFCE5E88B43871AC6BA", target.assetId)
        assertEquals("npo1", target.channelId)
        assertTrue(target.isReplayAllowed)
        assertTrue(target.isRestartAllowed)
    }
}
