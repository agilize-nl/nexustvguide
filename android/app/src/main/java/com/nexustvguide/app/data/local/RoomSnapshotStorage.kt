package com.nexustvguide.app.data.local

import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.core.domain.DaySnapshot
import com.nexustvguide.app.core.domain.NlzietProgrammeTarget
import com.nexustvguide.app.core.domain.Programme
import com.nexustvguide.app.core.refresh.SnapshotStorage
import org.threeten.bp.Instant
import org.threeten.bp.OffsetDateTime

class RoomSnapshotStorage(
    private val guideDao: GuideDao
) : SnapshotStorage {

    override suspend fun getExistingProgrammeCount(date: String): Int? {
        val meta = guideDao.getDayMeta(date)
        return meta?.programmeCount
    }

    override suspend fun getSnapshot(date: String): DaySnapshot? {
        val meta = guideDao.getDayMeta(date) ?: return null
        val channels = guideDao.getChannels().map { c ->
            Channel(
                id = c.id,
                sourceId = c.sourceId,
                name = c.name,
                logoUrl = c.logoUrl,
                inNlziet = c.inNlziet,
                nlzietSlug = c.nlzietSlug,
                nlzietChannelId = c.nlzietChannelId,
                sortOrder = c.sortOrder
            )
        }
        val programmeEntities = guideDao.getProgrammesForDate(date, meta.fromUtcMs, meta.toUtcMs)
        val programmes = programmeEntities.map { entityToDomainProgramme(it) }

        val fromIso = Instant.ofEpochMilli(meta.fromUtcMs).toString()
        val toIso = Instant.ofEpochMilli(meta.toUtcMs).toString()
        val sourceFetchedAtIso = Instant.ofEpochMilli(meta.sourceFetchedAtMs).toString()
        val publishedAtIso = Instant.ofEpochMilli(meta.publishedAtMs).toString()

        return DaySnapshot(
            date = date,
            timeZone = "Europe/Amsterdam",
            from = fromIso,
            to = toIso,
            sourceFetchedAt = sourceFetchedAtIso,
            publishedAt = publishedAtIso,
            channels = channels,
            programmes = programmes
        )
    }

    override suspend fun saveDaySnapshot(snapshot: DaySnapshot) {
        val fromMs = OffsetDateTime.parse(snapshot.from).toInstant().toEpochMilli()
        val toMs = OffsetDateTime.parse(snapshot.to).toInstant().toEpochMilli()
        val sourceFetchedAtMs = OffsetDateTime.parse(snapshot.sourceFetchedAt).toInstant().toEpochMilli()
        val publishedAtMs = OffsetDateTime.parse(snapshot.publishedAt).toInstant().toEpochMilli()

        val metaEntity = DayMetaEntity(
            date = snapshot.date,
            fromUtcMs = fromMs,
            toUtcMs = toMs,
            sourceFetchedAtMs = sourceFetchedAtMs,
            publishedAtMs = publishedAtMs,
            programmeCount = snapshot.programmes.size
        )

        val programmeEntities = snapshot.programmes.map { p ->
            val startMs = OffsetDateTime.parse(p.start).toInstant().toEpochMilli()
            val endMs = OffsetDateTime.parse(p.end).toInstant().toEpochMilli()

            ProgrammeEntity(
                date = snapshot.date,
                id = p.id,
                channelId = p.channelId,
                title = p.title,
                startUtcMs = startMs,
                endUtcMs = endMs,
                description = p.description,
                imageUrl = p.imageUrl,
                genre = p.genre,
                isLive = p.isLive,
                isRerun = p.isRerun,
                isPremiere = p.isPremiere,
                ageRating = p.ageRating,
                nlzietKind = p.nlziet?.kind,
                nlzietContentItemId = p.nlziet?.contentItemId,
                nlzietAssetId = p.nlziet?.assetId,
                nlzietChannelId = p.nlziet?.channelId,
                nlzietReplayAllowed = p.nlziet?.isReplayAllowed ?: false,
                nlzietRestartAllowed = p.nlziet?.isRestartAllowed ?: false
            )
        }

        guideDao.replaceDay(snapshot.date, metaEntity, programmeEntities)
    }

    override suspend fun cleanOldSnapshots(minAllowedDate: String) {
        guideDao.cleanOldDays(minAllowedDate)
    }

    private fun entityToDomainProgramme(e: ProgrammeEntity): Programme {
        val startIso = Instant.ofEpochMilli(e.startUtcMs).toString()
        val endIso = Instant.ofEpochMilli(e.endUtcMs).toString()

        val target = if (!e.nlzietContentItemId.isNullOrEmpty() && !e.nlzietAssetId.isNullOrEmpty() && !e.nlzietChannelId.isNullOrEmpty()) {
            NlzietProgrammeTarget(
                kind = e.nlzietKind ?: "replay",
                contentItemId = e.nlzietContentItemId,
                assetId = e.nlzietAssetId,
                channelId = e.nlzietChannelId,
                isReplayAllowed = e.nlzietReplayAllowed,
                isRestartAllowed = e.nlzietRestartAllowed
            )
        } else null

        return Programme(
            id = e.id,
            channelId = e.channelId,
            title = e.title,
            start = startIso,
            end = endIso,
            description = e.description,
            imageUrl = e.imageUrl,
            genre = e.genre,
            isLive = e.isLive,
            isRerun = e.isRerun,
            isPremiere = e.isPremiere,
            ageRating = e.ageRating,
            nlziet = target,
            nlzietId = null
        )
    }
}
