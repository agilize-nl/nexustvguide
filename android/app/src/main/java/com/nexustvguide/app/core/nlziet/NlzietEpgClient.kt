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

class NlzietEpgClient(
    private val options: NlzietEpgClientOptions = NlzietEpgClientOptions()
) {
    private val client: OkHttpClient = options.okHttpClient ?: OkHttpClient.Builder()
        .callTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val cache = mutableMapOf<String, CacheEntry>()

    fun isDateInEpgWindow(dateStr: String, todayStr: String? = null): Boolean {
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

    suspend fun fetchEpg(dateStr: String, channelIds: List<String>): NlzietEpgResponse {
        val validChannelIds = channelIds.filter { it.isNotBlank() }.distinct().sorted()
        if (validChannelIds.isEmpty()) {
            return NlzietEpgResponse(emptyList())
        }

        val today = GuideTime.getTodayAmsterdam(options.nowFn)
        if (!isDateInEpgWindow(dateStr, today)) {
            return NlzietEpgResponse(emptyList())
        }

        val cacheKey = "$dateStr:${validChannelIds.joinToString(",")}"
        val nowMs = options.nowFn().toEpochMilli()
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

        val url = httpUrlBuilder.build().toString()
        var lastException: Exception? = null

        for (attempt in 0..options.maxRetries) {
            if (attempt > 0) {
                val backoffMs = min(500.0 * 2.0.pow(attempt - 1), 2000.0).toLong()
                delay(backoffMs)
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", options.userAgent)
                .header("Accept", "application/json")
                .build()

            try {
                client.newCall(request).execute().use { response ->
                    val statusCode = response.code
                    if (response.isSuccessful) {
                        val bodyString = response.body?.string()
                            ?: throw IOException("Empty body from NLZIET EPG")
                        val validated = NlzietEpgParser.parseNlzietEpgResponse(bodyString)

                        val ttlMs = calculateTtlMs(dateStr, today)
                        cache[cacheKey] = CacheEntry(
                            expiresAtMs = nowMs + ttlMs,
                            data = validated
                        )
                        return validated
                    }

                    if (statusCode in 500..599 && attempt < options.maxRetries) {
                        lastException = IOException("NLZIET EPG upstream HTTP 5xx: $statusCode ${response.message}")
                        return@use
                    }

                    throw IOException("NLZIET EPG HTTP error: $statusCode ${response.message}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt == options.maxRetries) {
                    throw IOException("NLZIET EPG fetch failed for date $dateStr after ${options.maxRetries + 1} attempts: ${e.message}", e)
                }
            }
        }

        throw lastException ?: IOException("NLZIET EPG fetch failed for date $dateStr")
    }
}
