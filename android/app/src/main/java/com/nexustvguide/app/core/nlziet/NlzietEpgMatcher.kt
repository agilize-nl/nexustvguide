package com.nexustvguide.app.core.nlziet

import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.core.domain.NlzietProgrammeTarget
import com.nexustvguide.app.core.domain.Programme
import org.threeten.bp.OffsetDateTime
import java.text.Normalizer
import java.util.Locale
import kotlin.math.abs

data class MatchCandidate(
    val content: NlzietEpgContent,
    val startDiffMs: Long,
    val durationDiffMs: Long
)

data class MatchResult(
    val target: NlzietProgrammeTarget?,
    val isAmbiguous: Boolean,
    val isTimingOrTitleMismatch: Boolean
)

data class EnrichmentStats(
    val totalProgrammes: Int,
    val epgEligibleProgrammes: Int,
    val exactTargets: Int,
    val replayAllowedTargets: Int,
    val rejectedAmbiguous: Int,
    val rejectedTitleOrTiming: Int,
    val skippedOutsideEpgWindow: Int,
    val epgFetchFailed: Boolean,
    val enrichedProgrammes: Int,
    val enrichmentRate: Double
)

object NlzietEpgMatcher {
    const val MAX_START_DIFF_MS = 6 * 60 * 1000L      // 6 minuten
    const val MAX_DURATION_DIFF_MS = 10 * 60 * 1000L  // 10 minuten

    private val PREFIX_REGEX = Regex(
        "^(nos|avrotros|bnnvara|kro-ncrv|kro|ncrv|vpro|max|omroep max|eo|npo|rtl\\s*\\d*|sbs\\s*\\d*|viaplay|canvas|vrt|powned|wnl|human|veronica|net\\s*5|een)\\s*([:\\-–—]\\s*|\\s+)",
        RegexOption.IGNORE_CASE
    )

    private val SUFFIX_REGEX = Regex(
        "\\s*[:\\-–—]\\s*(afl\\.?|aflevering|seizoen|season|s\\d+|deel|extra|special|live|herhaling|compilatie|serie|film).*$",
        RegexOption.IGNORE_CASE
    )

    private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
    private val COMBINING_DIACRITICS = Regex("[\\p{InCombiningDiacriticalMarks}̀-ͯ]")

    fun normalizeEpgTitle(title: String?): String {
        if (title.isNullOrBlank()) return ""
        val nfd = Normalizer.normalize(title, Normalizer.Form.NFD)
        val withoutDiacritics = nfd.replace(COMBINING_DIACRITICS, "")
        val lower = withoutDiacritics.lowercase(Locale.ROOT)
        val withoutPrefix = lower.replace(PREFIX_REGEX, "")
        val withoutSuffix = withoutPrefix.replace(SUFFIX_REGEX, "")
        val alphanumeric = withoutSuffix.replace(NON_ALPHANUMERIC, " ")
        return alphanumeric.trim()
    }

    fun matchProgramme(
        programme: Programme,
        epgItems: List<NlzietEpgContent>,
        nlzietChannelId: String
    ): MatchResult {
        if (programme.title.isEmpty() || programme.start.isEmpty() || programme.end.isEmpty()) {
            return MatchResult(target = null, isAmbiguous = false, isTimingOrTitleMismatch = true)
        }

        val progStartMs: Long
        val progEndMs: Long
        try {
            progStartMs = OffsetDateTime.parse(programme.start).toInstant().toEpochMilli()
            progEndMs = OffsetDateTime.parse(programme.end).toInstant().toEpochMilli()
        } catch (e: Exception) {
            return MatchResult(target = null, isAmbiguous = false, isTimingOrTitleMismatch = true)
        }

        val progDurationMs = progEndMs - progStartMs
        val normProgTitle = normalizeEpgTitle(programme.title)

        if (normProgTitle.isEmpty()) {
            return MatchResult(target = null, isAmbiguous = false, isTimingOrTitleMismatch = true)
        }

        val candidates = mutableListOf<MatchCandidate>()

        for (item in epgItems) {
            if (item.contentItemId.isEmpty() || item.assetId.isEmpty() || item.startAt.isEmpty() || item.endAt.isEmpty()) {
                continue
            }

            val epgStartMs: Long
            val epgEndMs: Long
            try {
                epgStartMs = OffsetDateTime.parse(item.startAt).toInstant().toEpochMilli()
                epgEndMs = OffsetDateTime.parse(item.endAt).toInstant().toEpochMilli()
            } catch (e: Exception) {
                continue
            }

            val epgDurationMs = epgEndMs - epgStartMs
            val startDiffMs = abs(progStartMs - epgStartMs)
            val durationDiffMs = abs(progDurationMs - epgDurationMs)

            if (startDiffMs > MAX_START_DIFF_MS) continue
            if (durationDiffMs > MAX_DURATION_DIFF_MS) continue

            val normEpgTitle = normalizeEpgTitle(item.title)
            val titleMatches = normProgTitle == normEpgTitle ||
                    normProgTitle.replace(" ", "") == normEpgTitle.replace(" ", "")

            if (titleMatches) {
                candidates.add(MatchCandidate(content = item, startDiffMs = startDiffMs, durationDiffMs = durationDiffMs))
            }
        }

        return when {
            candidates.size == 1 -> {
                val best = candidates[0].content
                val target = NlzietProgrammeTarget(
                    kind = "replay",
                    contentItemId = best.contentItemId,
                    assetId = best.assetId,
                    channelId = nlzietChannelId,
                    isReplayAllowed = best.isReplayAllowed,
                    isRestartAllowed = best.isRestartAllowed
                )
                MatchResult(target = target, isAmbiguous = false, isTimingOrTitleMismatch = false)
            }
            candidates.size > 1 -> {
                MatchResult(target = null, isAmbiguous = true, isTimingOrTitleMismatch = false)
            }
            else -> {
                MatchResult(target = null, isAmbiguous = false, isTimingOrTitleMismatch = true)
            }
        }
    }

    fun enrichProgrammes(
        programmes: List<Programme>,
        epgResponse: NlzietEpgResponse?,
        channels: List<Channel> = emptyList(),
        isOutsideEpgWindow: Boolean = false,
        epgFetchFailed: Boolean = false
    ): EnrichmentStats {
        val totalProgrammes = programmes.size
        val channelMap = channels.associateBy { it.id }

        if (epgFetchFailed) {
            for (p in programmes) {
                p.nlziet = null
                p.nlzietId = null
            }
            return EnrichmentStats(
                totalProgrammes = totalProgrammes,
                epgEligibleProgrammes = 0,
                exactTargets = 0,
                replayAllowedTargets = 0,
                rejectedAmbiguous = 0,
                rejectedTitleOrTiming = 0,
                skippedOutsideEpgWindow = 0,
                epgFetchFailed = true,
                enrichedProgrammes = 0,
                enrichmentRate = 0.0
            )
        }

        if (isOutsideEpgWindow || epgResponse == null) {
            for (p in programmes) {
                p.nlziet = null
                p.nlzietId = null
            }
            return EnrichmentStats(
                totalProgrammes = totalProgrammes,
                epgEligibleProgrammes = 0,
                exactTargets = 0,
                replayAllowedTargets = 0,
                rejectedAmbiguous = 0,
                rejectedTitleOrTiming = 0,
                skippedOutsideEpgWindow = totalProgrammes,
                epgFetchFailed = false,
                enrichedProgrammes = 0,
                enrichmentRate = 0.0
            )
        }

        // Indexeer EPG items per NLZIET channel ID
        val epgByChannel = mutableMapOf<String, MutableList<NlzietEpgContent>>()
        for (group in epgResponse.data) {
            val chId = group.channelId
            if (chId.isEmpty()) continue
            val list = epgByChannel.getOrPut(chId) { mutableListOf() }
            for (loc in group.programLocations) {
                list.add(loc.content)
            }
        }

        var epgEligible = 0
        var exactTargets = 0
        var replayAllowedTargets = 0
        var rejectedAmbiguous = 0
        var rejectedTitleOrTiming = 0

        for (p in programmes) {
            val ch = channelMap[p.channelId]
            val nlzietChId = ch?.nlzietChannelId

            if (ch == null || !ch.inNlziet || nlzietChId.isNullOrEmpty()) {
                p.nlziet = null
                p.nlzietId = null
                continue
            }

            epgEligible++
            val epgItems = epgByChannel[nlzietChId] ?: emptyList()
            val matchResult = matchProgramme(p, epgItems, nlzietChId)

            if (matchResult.target != null) {
                p.nlziet = matchResult.target
                p.nlzietId = null // Oude legacy nlzietId niet meer vullen voor gidsklik
                exactTargets++
                if (matchResult.target.isReplayAllowed) {
                    replayAllowedTargets++
                }
            } else {
                p.nlziet = null
                p.nlzietId = null
                if (matchResult.isAmbiguous) {
                    rejectedAmbiguous++
                } else if (matchResult.isTimingOrTitleMismatch) {
                    rejectedTitleOrTiming++
                }
            }
        }

        return EnrichmentStats(
            totalProgrammes = totalProgrammes,
            epgEligibleProgrammes = epgEligible,
            exactTargets = exactTargets,
            replayAllowedTargets = replayAllowedTargets,
            rejectedAmbiguous = rejectedAmbiguous,
            rejectedTitleOrTiming = rejectedTitleOrTiming,
            skippedOutsideEpgWindow = 0,
            epgFetchFailed = false,
            enrichedProgrammes = exactTargets,
            enrichmentRate = if (totalProgrammes > 0) exactTargets.toDouble() / totalProgrammes else 0.0
        )
    }
}
