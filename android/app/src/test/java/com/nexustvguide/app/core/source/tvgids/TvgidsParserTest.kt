package com.nexustvguide.app.core.source.tvgids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvgidsParserTest {

    @Test
    fun `parses envelope with data as object`() {
        val json = """
        {
          "version": "1.0",
          "data": {
            "1": {
              "ch_id": "1",
              "prog": [
                {
                  "s": "1788043500",
                  "e": "1788047100",
                  "db_id": "218749412",
                  "title": "NOS Journaal",
                  "descr": "<p>Nieuwsbulletin</p>",
                  "ei": "AL"
                }
              ]
            }
          }
        }
        """.trimIndent()

        val stats = ValidationStats()
        val result = TvgidsParser.parseProgramsEnvelope(json, stats)

        assertEquals(1, result.size)
        assertTrue(result.containsKey("1"))
        val progs = result["1"]!!
        assertEquals(1, progs.size)
        assertEquals("218749412", progs[0].db_id)
        assertEquals("NOS Journaal", progs[0].title)
        assertEquals(0, stats.skippedMalformedProgrammesCount)
    }

    @Test
    fun `parses envelope with data as array`() {
        val json = """
        {
          "version": "1.0",
          "data": [
            {
              "ch_id": "2",
              "prog": [
                {
                  "s": "1788043500",
                  "e": "1788047100",
                  "db_id": "218749413",
                  "title": "BinnensteBuiten"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val stats = ValidationStats()
        val result = TvgidsParser.parseProgramsEnvelope(json, stats)

        assertEquals(1, result.size)
        assertTrue(result.containsKey("2"))
        assertEquals("218749413", result["2"]!![0].db_id)
    }

    @Test
    fun `skips malformed programmes and continues parsing remaining valid items`() {
        val json = """
        {
          "version": "1.0",
          "data": {
            "1": {
              "ch_id": "1",
              "prog": [
                { "s": "abc", "e": "1788047100", "db_id": "1" },
                { "s": "1788047100", "e": "1788043500", "db_id": "2" },
                { "s": "1788043500", "e": "1788047100", "db_id": "999999999999999999999999999999" },
                { "s": "1788043500", "e": "1788047100", "db_id": "218749412", "title": "Valid Show" }
              ]
            }
          }
        }
        """.trimIndent()

        val stats = ValidationStats()
        val result = TvgidsParser.parseProgramsEnvelope(json, stats)

        assertEquals(3, stats.skippedMalformedProgrammesCount)
        val progs = result["1"]!!
        assertEquals(1, progs.size)
        assertEquals("218749412", progs[0].db_id)
        assertEquals("Valid Show", progs[0].title)
    }
}
