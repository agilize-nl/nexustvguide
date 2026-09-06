package com.nexustvguide.app.core.source.tvgids

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FormattersTest {

    @Test
    fun `htmlToText strips HTML tags and decodes entities`() {
        val input = "<html><p>Vandaag &amp; morgen: &euro; 50,- voor een caf&eacute; bezoek met &#039;vrienden&#039; en &nbsp; test.</p></html>"
        val expected = "Vandaag & morgen: € 50,- voor een café bezoek met 'vrienden' en test."
        assertEquals(expected, Formatters.htmlToText(input))
    }

    @Test
    fun `htmlToText handles hex entities and normalizes whitespace`() {
        val input = "  <p>Prijs:   &#x20AC;   100  </p>  "
        assertEquals("Prijs: € 100", Formatters.htmlToText(input))
    }

    @Test
    fun `htmlToText returns null for null or empty strings`() {
        assertNull(Formatters.htmlToText(null))
        assertNull(Formatters.htmlToText(""))
        assertNull(Formatters.htmlToText("   "))
        assertNull(Formatters.htmlToText("<p></p>"))
    }

    @Test
    fun `normalizeAgeRating accepts valid ratings and normalizes casing and trimming`() {
        assertEquals("AL", Formatters.normalizeAgeRating("al"))
        assertEquals("AL", Formatters.normalizeAgeRating(" AL "))
        assertEquals("6", Formatters.normalizeAgeRating("6"))
        assertEquals("9", Formatters.normalizeAgeRating("9"))
        assertEquals("12", Formatters.normalizeAgeRating("12"))
        assertEquals("14", Formatters.normalizeAgeRating("14"))
        assertEquals("16", Formatters.normalizeAgeRating("16"))
        assertEquals("18", Formatters.normalizeAgeRating("18"))
    }

    @Test
    fun `normalizeAgeRating rejects invalid junk ratings`() {
        assertNull(Formatters.normalizeAgeRating(null))
        assertNull(Formatters.normalizeAgeRating(""))
        assertNull(Formatters.normalizeAgeRating(" "))
        assertNull(Formatters.normalizeAgeRating("H"))
        assertNull(Formatters.normalizeAgeRating("live"))
        assertNull(Formatters.normalizeAgeRating("tip"))
        assertNull(Formatters.normalizeAgeRating("15"))
    }
}
