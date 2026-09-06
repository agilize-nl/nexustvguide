package com.nexustvguide.app.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.core.nlziet.NlzietEpgClient
import com.nexustvguide.app.core.nlziet.NlzietEpgMatcher
import com.nexustvguide.app.core.refresh.RefreshCoordinator
import com.nexustvguide.app.core.refresh.RefreshEngine
import com.nexustvguide.app.core.refresh.StaleChecker
import com.nexustvguide.app.core.source.tvgids.TvgidsClient
import com.nexustvguide.app.core.time.GuideTime
import com.nexustvguide.app.data.local.AppDatabase
import com.nexustvguide.app.data.local.ChannelEntity
import com.nexustvguide.app.data.local.DayMetaEntity
import com.nexustvguide.app.data.local.GuideDao
import com.nexustvguide.app.data.local.ProgrammeEntity
import com.nexustvguide.app.data.local.RoomSnapshotStorage
import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.GuideMetaDto
import com.nexustvguide.app.data.model.GuideResponseDto
import com.nexustvguide.app.data.model.NlzietProgrammeTargetDto
import com.nexustvguide.app.data.model.ProgrammeDto
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.threeten.bp.Instant

class LocalGuideRepository(
    private val context: Context,
    private val guideDao: GuideDao = AppDatabase.getInstance(context).guideDao(),
    private val coordinator: RefreshCoordinator = RefreshCoordinator(
        RefreshEngine(
            tvgidsClient = TvgidsClient(),
            epgClient = NlzietEpgClient(),
            epgMatcher = NlzietEpgMatcher,
            storage = RoomSnapshotStorage(guideDao),
            logger = { Log.d(TAG, it) }
        )
    ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GuideRepository {

    private val gson = Gson()
    private val scope = CoroutineScope(Dispatchers.IO)

    companion object {
        private const val TAG = "LocalGuideRepository"
    }

    val refreshCoordinator: RefreshCoordinator get() = coordinator

    override suspend fun getChannels(): List<ChannelDto> = withContext(ioDispatcher) {
        val existing = guideDao.getChannels()
        if (existing.isNotEmpty()) {
            return@withContext existing.map { it.toDto() }
        }

        // Initieel laden uit assets/channels.json
        val channelsFromAsset = loadChannelsFromAssets()
        if (channelsFromAsset.isNotEmpty()) {
            guideDao.insertChannels(channelsFromAsset.map { it.toEntity() })
            return@withContext channelsFromAsset
        }

        emptyList()
    }

    override suspend fun getChannelsForOrdering(date: String): List<ChannelDto> = withContext(ioDispatcher) {
        getChannels()
    }

    override suspend fun getGuideForDate(date: String): GuideResponseDto? = withContext(ioDispatcher) {
        ensureChannelsSeeded()

        val meta = guideDao.getDayMeta(date)
        if (meta == null) {
            // Geen snapshot aanwezig: direct ophalen met prioriteit voor deze datum
            Log.i(TAG, "No local snapshot found for $date, triggering ingest with priority")
            try {
                coordinator.refresh(getDomainChannels(), priorityDate = date)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                Log.e(TAG, "Ingest failed for date $date", e)
            }
            return@withContext loadResponseFromDatabase(date)
        }

        val today = GuideTime.getTodayAmsterdam()
        val nowMs = System.currentTimeMillis()
        val isStale = StaleChecker.isStale(meta.publishedAtMs, date, nowMs, today)

        if (isStale) {
            Log.i(TAG, "Snapshot for $date is stale, launching background refresh")
            scope.launch {
                try {
                    coordinator.refresh(getDomainChannels(), priorityDate = date)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.w(TAG, "Background refresh for $date failed", e)
                }
            }
        }

        loadResponseFromDatabase(date)
    }

    override fun observeGuideForDate(date: String): Flow<GuideResponseDto?> {
        val (fromInstant, toInstant) = GuideTime.getLocalDayUtcWindow(date)
        val fromMs = fromInstant.toEpochMilli()
        val toMs = toInstant.toEpochMilli()

        return combine(
            guideDao.observeDayMeta(date),
            guideDao.observeProgrammesForDate(date, fromMs, toMs)
        ) { meta, progs ->
            if (meta == null) {
                null
            } else {
                val today = GuideTime.getTodayAmsterdam()
                val nowMs = System.currentTimeMillis()
                val isStale = StaleChecker.isStale(meta.publishedAtMs, date, nowMs, today)
                val channels = getChannels()

                val programmeDtos = progs.map { p -> p.toDto() }

                val guideMeta = GuideMetaDto(
                    timeZone = GuideTime.TIME_ZONE,
                    date = date,
                    from = Instant.ofEpochMilli(meta.fromUtcMs).toString(),
                    to = Instant.ofEpochMilli(meta.toUtcMs).toString(),
                    lastSuccessfulRefresh = Instant.ofEpochMilli(meta.publishedAtMs).toString(),
                    stale = isStale
                )

                GuideResponseDto(
                    meta = guideMeta,
                    channels = channels,
                    programmes = programmeDtos
                )
            }
        }.flowOn(ioDispatcher)
    }

    private suspend fun ensureChannelsSeeded() {
        if (guideDao.getChannels().isEmpty()) {
            val fromAsset = loadChannelsFromAssets()
            if (fromAsset.isNotEmpty()) {
                guideDao.insertChannels(fromAsset.map { it.toEntity() })
            }
        }
    }

    private suspend fun getDomainChannels(): List<Channel> {
        return getChannels().map { it.toDomain() }
    }

    private suspend fun loadResponseFromDatabase(date: String): GuideResponseDto? {
        val meta = guideDao.getDayMeta(date) ?: return null
        val channels = getChannels()
        val progs = guideDao.getProgrammesForDate(date, meta.fromUtcMs, meta.toUtcMs)

        val today = GuideTime.getTodayAmsterdam()
        val nowMs = System.currentTimeMillis()
        val isStale = StaleChecker.isStale(meta.publishedAtMs, date, nowMs, today)

        val guideMeta = GuideMetaDto(
            timeZone = GuideTime.TIME_ZONE,
            date = date,
            from = Instant.ofEpochMilli(meta.fromUtcMs).toString(),
            to = Instant.ofEpochMilli(meta.toUtcMs).toString(),
            lastSuccessfulRefresh = Instant.ofEpochMilli(meta.publishedAtMs).toString(),
            stale = isStale
        )

        return GuideResponseDto(
            meta = guideMeta,
            channels = channels,
            programmes = progs.map { it.toDto() }
        )
    }

    private fun loadChannelsFromAssets(): List<ChannelDto> {
        return try {
            context.assets.open("channels.json").use { inputStream ->
                val json = inputStream.bufferedReader().use { it.readText() }
                val type = object : TypeToken<List<ChannelDto>>() {}.type
                gson.fromJson(json, type)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load channels.json from assets", e)
            emptyList()
        }
    }

    private fun ChannelEntity.toDto() = ChannelDto(
        id = id,
        sourceId = sourceId,
        name = name,
        logoUrl = logoUrl,
        inNlziet = inNlziet,
        nlzietSlug = nlzietSlug,
        sortOrder = sortOrder,
        nlzietChannelId = nlzietChannelId
    )

    private fun ChannelDto.toEntity() = ChannelEntity(
        id = id,
        sourceId = sourceId,
        name = name,
        logoUrl = logoUrl,
        inNlziet = inNlziet,
        nlzietSlug = nlzietSlug,
        nlzietChannelId = nlzietChannelId,
        sortOrder = sortOrder
    )

    private fun ChannelDto.toDomain() = Channel(
        id = id,
        sourceId = sourceId,
        name = name,
        logoUrl = logoUrl,
        inNlziet = inNlziet,
        nlzietSlug = nlzietSlug,
        nlzietChannelId = nlzietChannelId,
        sortOrder = sortOrder
    )

    private fun ProgrammeEntity.toDto(): ProgrammeDto {
        val targetDto = if (!nlzietContentItemId.isNullOrEmpty() && !nlzietAssetId.isNullOrEmpty() && !nlzietChannelId.isNullOrEmpty()) {
            NlzietProgrammeTargetDto(
                kind = nlzietKind ?: "replay",
                contentItemId = nlzietContentItemId,
                assetId = nlzietAssetId,
                channelId = nlzietChannelId,
                isReplayAllowed = nlzietReplayAllowed,
                isRestartAllowed = nlzietRestartAllowed
            )
        } else null

        return ProgrammeDto(
            id = id,
            channelId = channelId,
            title = title,
            start = Instant.ofEpochMilli(startUtcMs).toString(),
            end = Instant.ofEpochMilli(endUtcMs).toString(),
            description = description,
            imageUrl = imageUrl,
            genre = genre,
            isLive = isLive,
            isRerun = isRerun,
            isPremiere = isPremiere,
            ageRating = ageRating,
            nlziet = targetDto,
            nlzietId = null
        )
    }
}
