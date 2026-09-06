package com.nexustvguide.app.core.nlziet

import com.nexustvguide.app.core.time.GuideTime
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.threeten.bp.Instant
import org.threeten.bp.LocalDate
import org.threeten.bp.temporal.ChronoUnit
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

data class NlzietEpgClientOptions(
    val baseUrl: String = "https://api.nlziet.nl/v9/epg/programlocations",
    val timeoutMs: Long = 10_000L,
    val maxRetries: Int = 2,
    val userAgent: String = "NexusTVGuide/1.0",
    val okHttpClient: OkHttpClient? = null,
    val nowFn: () -> Instant = { Instant.now() }
)

private data class CacheEntry(
    val expiresAtMs: Long,
    val data: NlzietEpgResponse
)

interface EpgSource {
    fun isDateInEpgWindow(dateStr: String, todayStr: String? = null): Boolean
    suspend fun fetchEpg(dateStr: String, channelIds: List<String>, todayStr: String? = null): NlzietEpgResponse
}

class NlzietEpgClient(
    private val options: NlzietEpgClientOptions = NlzietEpgClientOptions()
) : EpgSource {
    private val client = (options.okHttpClient ?: OkHttpClient()).newBuilder()
        .callTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val fetcher = com.nexustvguide.app.core.http.HttpFetcher(client, options.maxRetries, 500, 2000)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, CacheEntry>()

    override fun isDateInEpgWindow(dateStr: String, todayStr: String?): Boolean {
        val today = todayStr ?: GuideTime.getTodayAmsterdam(options.nowFn)
        val todayDate = LocalDate.parse(today)
        val targetDate = LocalDate.parse(dateStr)
        val diffDays = ChronoUnit.DAYS.between(todayDate, targetDate)
        return diffDays in -7..7
    }

    private fun calculateTtlMs(dateStr: String, todayStr: String): Long {
        return when {
            dateStr == todayStr -> 10 * 60 * 1000L      // 10 minuten voor vandaag
            dateStr > todayStr -> 60 * 60 * 1000L       // 60 minuten voor de toekomst
            else -> 6 * 60 * 60 * 1000L                 // 6 uur voor het verleden
        }
    }

    fun clearCache() {
        cache.clear()
    }

    override suspend fun fetchEpg(dateStr: String, channelIds: List<String>, todayStr: String?): NlzietEpgResponse {
        val validChannelIds = channelIds.filter { it.isNotBlank() }.distinct().sorted()
        if (validChannelIds.isEmpty()) {
            return NlzietEpgResponse(emptyList())
        }

        val today = todayStr ?: GuideTime.getTodayAmsterdam(options.nowFn)
        if (!isDateInEpgWindow(dateStr, today)) {
            return NlzietEpgResponse(emptyList())
        }

        val cacheKey = "$dateStr:${validChannelIds.joinToString(",")}"
        val nowMs = options.nowFn().toEpochMilli()
        cache.entries.forEach { if (it.value.expiresAtMs <= nowMs) cache.remove(it.key, it.value) }
        val cached = cache[cacheKey]
        if (cached != null && cached.expiresAtMs > nowMs) {
            return cached.data
        }

        val httpUrlBuilder = options.baseUrl.toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Invalid base URL: ${options.baseUrl}")

        httpUrlBuilder.addQueryParameter("date", dateStr)
        for (chId in validChannelIds) {
            httpUrlBuilder.addQueryParameter("channel", chId)
        }

        val request = Request.Builder().url(httpUrlBuilder.build())
            .header("User-Agent", options.userAgent).header("Accept", "application/json").build()
        // Validation stays outside the HTTP retry loop and precedes caching.
        val validated = NlzietEpgParser.parseNlzietEpgResponse(fetcher.fetch(request))
        cache[cacheKey] = CacheEntry(options.nowFn().toEpochMilli() + calculateTtlMs(dateStr, today), validated)
        return validated
    }
}
