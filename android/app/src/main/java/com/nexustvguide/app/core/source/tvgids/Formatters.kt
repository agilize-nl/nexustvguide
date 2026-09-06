package com.nexustvguide.app.core.source.tvgids

import org.jsoup.parser.Parser

object Formatters {
    private val VALID_AGE_RATINGS = setOf("AL", "6", "9", "12", "14", "16", "18")
    private val TAG_REGEX = Regex("<[^>]*>")
    private val WHITESPACE_REGEX = Regex("[\\s\\u00A0]+")

    /**
     * Verwijdert HTML tags en decodeert HTML entities (zoals &amp;, &eacute;, &#039;, &euro;).
     * Exact gelijk aan he.decode() in Node.js.
     */
    fun htmlToText(html: String?): String? {
        if (html.isNullOrEmpty()) return null
        val stripped = html.replace(TAG_REGEX, " ")
        val decoded = Parser.unescapeEntities(stripped, false)
        val normalized = decoded.replace(WHITESPACE_REGEX, " ").trim { it <= ' ' || it == '\u00A0' }
        return if (normalized.isNotEmpty()) normalized else null
    }

    /**
     * Normaliseert Kijkwijzer rating ('ei' veld).
     * Alleen 'AL', '6', '9', '12', '14', '16', '18' zijn geldig.
     * Ongeldige waarden zoals 'H', 'live', 'tip', '' worden genormaliseerd naar null.
     */
    fun normalizeAgeRating(rawEi: String?): String? {
        if (rawEi.isNullOrBlank()) return null
        val cleaned = rawEi.trim().uppercase()
        return if (VALID_AGE_RATINGS.contains(cleaned)) cleaned else null
    }
}
