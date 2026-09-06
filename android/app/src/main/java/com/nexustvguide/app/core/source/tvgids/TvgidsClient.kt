package com.nexustvguide.app.core.source.tvgids

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.math.min
import kotlin.math.pow

data class TvgidsClientOptions(
    val baseUrl: String = "https://json.tvgids.nl/v4",
    val timeoutMs: Long = 10_000L,
    val maxRetries: Int = 2,
    val userAgent: String = "NexusTVGuide/1.0 (+https://github.com/NexusTVGuide)",
    val okHttpClient: OkHttpClient? = null
)

fun interface ProgrammeSource {
    suspend fun fetchPrograms(dayOffset: Int, channelSourceIds: List<String>?): String
}

class TvgidsClient(
    private val options: TvgidsClientOptions = TvgidsClientOptions()
) : ProgrammeSource {
    private val client = (options.okHttpClient ?: OkHttpClient()).newBuilder()
        .callTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()
    private val fetcher = com.nexustvguide.app.core.http.HttpFetcher(client, options.maxRetries, 1000, 3000)
    private val baseUrl = options.baseUrl.trimEnd('/')

    private suspend fun fetchWithRetry(url: String): String = fetcher.fetch(
        Request.Builder().url(url).header("User-Agent", options.userAgent)
            .header("Accept", "application/json").build()
    )

    suspend fun fetchChannels(): String {
        val url = "$baseUrl/channels"
        return fetchWithRetry(url)
    }

    override suspend fun fetchPrograms(dayOffset: Int, channelSourceIds: List<String>?): String {
        val httpUrlBuilder = "$baseUrl/programs/".toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Invalid base URL: $baseUrl")

        httpUrlBuilder.addQueryParameter("day", dayOffset.toString())
        if (!channelSourceIds.isNullOrEmpty()) {
            httpUrlBuilder.addQueryParameter("channels", channelSourceIds.joinToString(","))
        }

        return fetchWithRetry(httpUrlBuilder.build().toString())
    }
}
