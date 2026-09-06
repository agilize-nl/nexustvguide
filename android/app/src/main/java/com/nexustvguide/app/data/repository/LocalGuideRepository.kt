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
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import com.nexustvguide.app.core.refresh.RefreshStatus
import com.nexustvguide.app.data.local.StoredDay
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
        ),
        onStatus = { status -> persistStatus(context, status) }
    ),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : GuideRepository {

    private val gson = Gson()
    private val seedMutex = Mutex()
    private var channelsSeeded = false

    companion object {
        private const val TAG = "LocalGuideRepository"

        private fun persistStatus(context: Context, status: RefreshStatus) {
            val result = status.result
            val json = Gson().toJson(mapOf(
                "attemptAt" to System.currentTimeMillis(), "running" to status.running,
                "error" to status.error, "successfulDays" to result?.successfulDays,
                "rejectedDays" to result?.rejectedDays, "sourceErrors" to result?.sourceErrors,
                "failedOffsets" to result?.failedOffsets, "epgErrors" to result?.epgErrors,
                "programmes" to result?.totalProgrammesIngested, "targets" to result?.totalExactTargets,
                "skippedMalformed" to result?.skippedMalformedProgrammesCount
            ))
            context.getSharedPreferences("local_refresh_status", Context.MODE_PRIVATE)
                .edit().putString("last_attempt", json).apply()
            Log.i(TAG, json)
        }
    }

    val refreshCoordinator: RefreshCoordinator get() = coordinator

    override suspend fun getChannels(): List<ChannelDto> = withContext(ioDispatcher) {
        ensureChannelsSeeded()
        guideDao.getChannels().map { it.toDto() }
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
            coordinator.request(getDomainChannels(), priorityDate = date)
        }
        loadResponseFromDatabase(date)
    }

    override fun observeGuideForDate(date: String): Flow<GuideResponseDto?> = combine(
        guideDao.observeDay(date),
        coordinator.status,
        flow {
            while (true) {
                emit(System.currentTimeMillis())
                delay(60_000)
            }
        }
    ) { day, status, nowMs ->
        day?.let { toResponse(it, status, nowMs) }
    }.flowOn(ioDispatcher)

    private suspend fun ensureChannelsSeeded() = seedMutex.withLock {
        if (!channelsSeeded) {
            // Reseed each process start so an APK's channel configuration updates existing databases.
            val channels = loadChannelsFromAssets()
            guideDao.insertChannels(channels.map { it.toEntity() })
            channelsSeeded = true
        }
    }

    private suspend fun getDomainChannels(): List<Channel> = getChannels().map { it.toDomain() }

    private suspend fun loadResponseFromDatabase(date: String): GuideResponseDto? =
        guideDao.getDay(date)?.let { toResponse(it, coordinator.status.value, System.currentTimeMillis()) }

    private suspend fun toResponse(day: StoredDay, status: RefreshStatus, nowMs: Long): GuideResponseDto {
        val meta = day.meta
        // Read-only: a stored day implies seeding already ran, so observing never writes.
        val channels = guideDao.getChannels().map { it.toDto() }
        val channelOrder = channels.associate { it.id to it.sortOrder }
        val failed = status.error != null || status.result?.let {
            it.sourceErrors.isNotEmpty() || meta.date in it.rejectedDays
        } == true
        val stale = failed || StaleChecker.isStale(meta.publishedAtMs, meta.date, nowMs, GuideTime.getTodayAmsterdam())
        val programmes = day.programmes.filter {
            it.channelId in channelOrder && it.startUtcMs < meta.toUtcMs && it.endUtcMs > meta.fromUtcMs
        }.sortedWith(compareBy<ProgrammeEntity> { channelOrder[it.channelId] }.thenBy { it.startUtcMs }.thenBy { it.id })
        return GuideResponseDto(
            meta = GuideMetaDto(
                timeZone = GuideTime.TIME_ZONE, date = meta.date,
                from = Instant.ofEpochMilli(meta.fromUtcMs).toString(),
                to = Instant.ofEpochMilli(meta.toUtcMs).toString(),
                lastSuccessfulRefresh = Instant.ofEpochMilli(meta.publishedAtMs).toString(), stale = stale
            ),
            channels = channels,
            programmes = programmes.map { it.toDto() }
        )
    }

    private fun loadChannelsFromAssets(): List<ChannelDto> {
        return context.assets.open("channels.json").bufferedReader().use { reader ->
            val type = object : TypeToken<List<ChannelDto>>() {}.type
            gson.fromJson<List<ChannelDto>>(reader, type).sortedBy { it.sortOrder }
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
