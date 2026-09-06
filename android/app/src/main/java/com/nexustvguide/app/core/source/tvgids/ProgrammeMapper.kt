package com.nexustvguide.app.core.source.tvgids

import com.nexustvguide.app.core.domain.Programme
import org.threeten.bp.Instant
import org.threeten.bp.format.DateTimeFormatter

object ProgrammeMapper {
    private val ISO_FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_INSTANT

    /**
     * Zet een gevalideerd RawProgramme om naar het domeinmodel Programme.
     * Dit is de enige plek in de app die de upstream tvgids-vorm kent.
     */
    fun mapProgramme(raw: RawProgramme, channelId: String): Programme {
        val startSec = raw.s.toLong()
        val endSec = raw.e.toLong()

        val startInstant = Instant.ofEpochSecond(startSec)
        val endInstant = Instant.ofEpochSecond(endSec)

        // Voorkeur voor beschrijving: algemene_inhoud -> inhoud -> htmlToText(descr)
        val desc = (raw.algemene_inhoud?.trim()?.takeIf { it.isNotEmpty() })
            ?: (raw.inhoud?.trim()?.takeIf { it.isNotEmpty() })
            ?: (raw.descr?.let { Formatters.htmlToText(it) })

        val title = raw.title?.trim()?.takeIf { it.isNotEmpty() } ?: "(Geen titel)"

        return Programme(
            id = raw.db_id,
            channelId = channelId,
            title = title,
            start = ISO_FORMATTER.format(startInstant),
            end = ISO_FORMATTER.format(endInstant),
            description = desc,
            imageUrl = raw.img?.trim()?.takeIf { it.isNotEmpty() },
            genre = raw.subgenre?.trim()?.takeIf { it.isNotEmpty() },
            isLive = raw.live == "true",
            isRerun = raw.rerun == "true",
            isPremiere = raw.is_premiere == "true",
            ageRating = Formatters.normalizeAgeRating(raw.ei),
            nlziet = null,
            nlzietId = null
        )
    }
}
