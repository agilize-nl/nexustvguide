package com.nexustvguide.app.core.refresh

import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.core.domain.DaySnapshot
import com.nexustvguide.app.core.domain.Programme
import com.nexustvguide.app.core.nlziet.NlzietEpgClient
import com.nexustvguide.app.core.nlziet.NlzietEpgMatcher
import com.nexustvguide.app.core.nlziet.NlzietEpgResponse
import com.nexustvguide.app.core.source.tvgids.ProgrammeMapper
import com.nexustvguide.app.core.source.tvgids.TvgidsClient
import com.nexustvguide.app.core.source.tvgids.TvgidsParser
import com.nexustvguide.app.core.source.tvgids.ValidationStats
import com.nexustvguide.app.core.time.GuideTime
import kotlinx.coroutines.ensureActive
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime

data class RefreshResult(
    val successfulDays: List<String>,
    val rejectedDays: List<String>,
    val totalProgrammesIngested: Int,
    val totalExactTargets: Int,
    val epgFetchFailed: Boolean,
    val sourceErrors: List<String> = emptyList(),
    val retryableSourceFailure: Boolean = false,
    val skippedMalformedProgrammesCount: Int = 0,
    val failedOffsets: List<Int> = emptyList(),
    val epgErrors: List<String> = emptyList()
)

class RefreshEngine(
    private val tvgidsClient: com.nexustvguide.app.core.source.tvgids.ProgrammeSource,
    private val epgClient: com.nexustvguide.app.core.nlziet.EpgSource,
    private val epgMatcher: NlzietEpgMatcher,
    private val storage: SnapshotStorage,
    private val nowFn: () -> Instant = { Instant.now() },
    private val logger: (String) -> Unit = {}
) {
    companion object {
        const val MIN_PROVIDER_OFFSET = -2
        const val MAX_PROVIDER_OFFSET = 13
        const val MIN_CALENDAR_DAY_OFFSET = -2
        const val MAX_CALENDAR_DAY_OFFSET = 10
    }

    val validationStats = ValidationStats()

    suspend fun refreshAll(
        activeChannels: List<Channel>,
        priorityDate: String? = null
    ): RefreshResult {
        val cycleStart = nowFn()
        val today = GuideTime.getAmsterdamDateString(cycleStart)
        validationStats.skippedMalformedProgrammesCount = 0
        val failedOffsets = mutableListOf<Int>()
        val epgErrors = mutableListOf<String>()
        var retryableSourceFailure = false
        val sourceIds = activeChannels.map { it.sourceId }
        val sourceIdToChannel = activeChannels.associateBy { it.sourceId }
        val channelOrderMap = activeChannels.associate { it.id to it.sortOrder }

        val allProgrammesMap = mutableMapOf<String, Programme>()
        val sourceErrors = mutableListOf<String>()

        // 1. Haal alle offsets -2..13 op
        for (offset in MIN_PROVIDER_OFFSET..MAX_PROVIDER_OFFSET) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            try {
                val rawEnvelope = tvgidsClient.fetchPrograms(offset, sourceIds)
                val channelBuckets = TvgidsParser.parseProgramsEnvelope(rawEnvelope, validationStats)

                for ((sourceId, rawProgs) in channelBuckets) {
                    val domainChannel = sourceIdToChannel[sourceId] ?: continue
                    for (rawProg in rawProgs) {
                        val mapped = ProgrammeMapper.mapProgramme(rawProg, domainChannel.id)
                        val key = "${domainChannel.id}:${mapped.id}"
                        allProgrammesMap[key] = mapped
                    }
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                failedOffsets.add(offset)
                retryableSourceFailure = retryableSourceFailure || com.nexustvguide.app.core.http.isTransientSourceFailure(e)
                val msg = "Warning: failed to fetch provider offset $offset: ${e.message}"
                logger(msg)
                sourceErrors.add(msg)
                if (e is com.nexustvguide.app.core.http.HttpFailure && e.status == 429) {
                    failedOffsets.addAll((offset + 1)..MAX_PROVIDER_OFFSET)
                    break
                }
            }
        }

        val allProgrammes = allProgrammesMap.values.toList()
        val nowInstant = nowFn()
        val nowUtcIso = GuideTime.formatUtcIso(nowInstant)

        // 2. Bepaal kalenderdagen -2..10
        val datesToProcess = mutableListOf<String>()
        for (d in MIN_CALENDAR_DAY_OFFSET..MAX_CALENDAR_DAY_OFFSET) {
            datesToProcess.add(GuideTime.addDays(today, d.toLong()))
        }

        // Als een prioriteitsdatum is opgegeven, verwerk die eerst
        val orderedDates = if (priorityDate != null && datesToProcess.contains(priorityDate)) {
            listOf(priorityDate) + (datesToProcess - priorityDate)
        } else {
            datesToProcess
        }

        val nlzietChannelIds = activeChannels
            .mapNotNull { it.nlzietChannelId }
            .filter { it.isNotBlank() }
            .distinct()

        val successfulDays = mutableListOf<String>()
        val rejectedDays = mutableListOf<String>()
        var totalExactTargets = 0
        var anyEpgFailed = false

        // 3. Verdeel over lokale kalenderdagen en verrijk
        for (date in orderedDates) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            val (fromInstant, toInstant) = GuideTime.getLocalDayUtcWindow(date)
            val fromMs = fromInstant.toEpochMilli()
            val toMs = toInstant.toEpochMilli()

            // Overlap: pStart < toMs && pEnd > fromMs
            val dayProgrammes = allProgrammes.filter { p ->
                try {
                    val pStart = OffsetDateTime.parse(p.start).toInstant().toEpochMilli()
                    val pEnd = OffsetDateTime.parse(p.end).toInstant().toEpochMilli()
                    pStart < toMs && pEnd > fromMs
                } catch (e: Exception) {
                    false
                }
            }.map { it.copy(nlziet = null, nlzietId = null) }.sortedWith(Comparator { a, b ->
                val chOrderA = channelOrderMap[a.channelId] ?: 999
                val chOrderB = channelOrderMap[b.channelId] ?: 999
                if (chOrderA != chOrderB) {
                    chOrderA.compareTo(chOrderB)
                } else {
                    val startA = OffsetDateTime.parse(a.start).toInstant().toEpochMilli()
                    val startB = OffsetDateTime.parse(b.start).toInstant().toEpochMilli()
                    if (startA != startB) {
                        startA.compareTo(startB)
                    } else {
                        a.id.compareTo(b.id)
                    }
                }
            })

            // Sanity check a: Minimaal 1 programma vereist voor nabije dagen (-1 t/m +5)
            val isNearDay = date >= GuideTime.addDays(today, -1) && date <= GuideTime.addDays(today, 5)
            if (isNearDay && dayProgrammes.isEmpty()) {
                logger("Sanity check failed for date $date: 0 programmes found. Preserving existing snapshot.")
                rejectedDays.add(date)
                continue
            }

            // Sanity check b: Geen daling > 40% ten opzichte van bestaande snapshot
            val existingCount = storage.getExistingProgrammeCount(date) ?: 0
            if (existingCount > 50) {
                val dropRatio = (existingCount - dayProgrammes.size).toDouble() / existingCount.toDouble()
                if (dropRatio > 0.4) {
                    logger("Sanity check failed for date $date: count dropped from $existingCount to ${dayProgrammes.size} (>40% drop). Preserving existing snapshot.")
                    rejectedDays.add(date)
                    continue
                }
            }

            // Sanity check c: Waarschuwing indien vandaag geen NPO 1 bevat
            if (date == today) {
                val hasNpo1 = dayProgrammes.any { it.channelId == "npo1" }
                if (!hasNpo1 && dayProgrammes.isNotEmpty()) {
                    logger("Sanity check warning for today $date: NPO 1 has no programmes.")
                }
            }

            if (dayProgrammes.isNotEmpty()) {
                val isInWindow = epgClient.isDateInEpgWindow(date, today)
                var epgResponse: NlzietEpgResponse? = null
                var epgFetchFailed = false

                if (isInWindow && nlzietChannelIds.isNotEmpty()) {
                    try {
                        epgResponse = epgClient.fetchEpg(date, nlzietChannelIds, today)
                    } catch (e: Exception) {
                        if (e is kotlinx.coroutines.CancellationException) throw e
                        epgErrors.add("$date: ${e.message}")
                        logger("NLZIET EPG fetch failed for date $date: ${e.message}")
                        epgFetchFailed = true
                        anyEpgFailed = true
                    }
                }

                val stats = epgMatcher.enrichProgrammes(
                    programmes = dayProgrammes,
                    epgResponse = epgResponse,
                    channels = activeChannels,
                    isOutsideEpgWindow = !isInWindow,
                    epgFetchFailed = epgFetchFailed
                )

                totalExactTargets += stats.exactTargets

                val snapshot = DaySnapshot(
                    date = date,
                    timeZone = GuideTime.TIME_ZONE,
                    from = GuideTime.formatUtcIso(fromInstant),
                    to = GuideTime.formatUtcIso(toInstant),
                    sourceFetchedAt = nowUtcIso,
                    publishedAt = GuideTime.formatUtcIso(nowFn()),
                    channels = activeChannels,
                    programmes = dayProgrammes
                )

                storage.saveDaySnapshot(snapshot)
                successfulDays.add(date)
            } else {
                rejectedDays.add(date)
            }
        }

        // Verwijder snapshots ouder dan vandaag - 3 dagen
        if (successfulDays.isNotEmpty()) {
            val purgeThreshold = GuideTime.addDays(today, -3)
            storage.cleanOldSnapshots(purgeThreshold)
        }

        return RefreshResult(
            successfulDays = successfulDays,
            rejectedDays = rejectedDays,
            totalProgrammesIngested = allProgrammes.size,
            totalExactTargets = totalExactTargets,
            epgFetchFailed = anyEpgFailed,
            sourceErrors = sourceErrors,
            retryableSourceFailure = retryableSourceFailure,
            skippedMalformedProgrammesCount = validationStats.skippedMalformedProgrammesCount,
            failedOffsets = failedOffsets,
            epgErrors = epgErrors
        )
    }
}
