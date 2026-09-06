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

class TvgidsClient(
    private val options: TvgidsClientOptions = TvgidsClientOptions()
) {
    private val client: OkHttpClient = options.okHttpClient ?: OkHttpClient.Builder()
        .callTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)
        .connectTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(options.timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val baseUrl: String = options.baseUrl.trimEnd('/')

    private suspend fun fetchWithRetry(url: String): String {
        var lastException: Exception? = null

        for (attempt in 0..options.maxRetries) {
            if (attempt > 0) {
                val delayMs = min(1000.0 * 2.0.pow(attempt - 1), 3000.0).toLong()
                delay(delayMs)
            }

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", options.userAgent)
                .header("Accept", "application/json")
                .build()

            var retryDelaySeconds: Long? = null
            try {
                var isSuccess = false
                var bodyString: String? = null
                var statusCode = 0
                var statusMessage = ""

                client.newCall(request).execute().use { response ->
                    statusCode = response.code
                    statusMessage = response.message
                    if (response.isSuccessful) {
                        isSuccess = true
                        bodyString = response.body?.string()
                            ?: throw IOException("Empty response body from $url")
                    } else if (statusCode == 429) {
                        retryDelaySeconds = response.header("Retry-After")?.toLongOrNull() ?: 3L
                    }
                }

                if (isSuccess && bodyString != null) {
                    return bodyString!!
                }

                if (statusCode == 429 && attempt < options.maxRetries) {
                    delay((retryDelaySeconds ?: 3L) * 1000L)
                    continue
                }

                if (statusCode in 500..599 && attempt < options.maxRetries) {
                    lastException = IOException("Upstream HTTP 5xx error: $statusCode $statusMessage")
                    continue
                }

                throw IOException("Upstream HTTP error: $statusCode $statusMessage")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastException = e
                if (attempt == options.maxRetries) {
                    throw IOException("Failed to fetch $url after ${options.maxRetries + 1} attempts: ${e.message}", e)
                }
            }
        }

        throw lastException ?: IOException("Failed to fetch $url")
    }

    suspend fun fetchChannels(): String {
        val url = "$baseUrl/channels"
        return fetchWithRetry(url)
    }

    suspend fun fetchPrograms(dayOffset: Int, channelSourceIds: List<String>? = null): String {
        val httpUrlBuilder = "$baseUrl/programs/".toHttpUrlOrNull()?.newBuilder()
            ?: throw IllegalArgumentException("Invalid base URL: $baseUrl")

        httpUrlBuilder.addQueryParameter("day", dayOffset.toString())
        if (!channelSourceIds.isNullOrEmpty()) {
            httpUrlBuilder.addQueryParameter("channels", channelSourceIds.joinToString(","))
        }

        return fetchWithRetry(httpUrlBuilder.build().toString())
    }
}
