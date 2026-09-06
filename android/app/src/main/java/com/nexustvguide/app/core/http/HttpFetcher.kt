package com.nexustvguide.app.core.http

import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.threeten.bp.ZonedDateTime
import org.threeten.bp.format.DateTimeFormatter
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class HttpFailure(val status: Int, val retryAfterMs: Long? = null) : IOException("Upstream HTTP $status") {
    val isTransient: Boolean get() = status == 429 || status in 500..599
}

fun isTransientSourceFailure(error: Exception): Boolean =
    if (error is HttpFailure) error.isTransient else error is IOException

/** One retry policy, including body reads; cancellation closes the active socket. */
class HttpFetcher(
    private val client: OkHttpClient,
    private val maxRetries: Int,
    private val initialBackoffMs: Long,
    private val maxBackoffMs: Long,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private data class Payload(val code: Int, val body: String, val retryAfter: String?)

    suspend fun fetch(request: Request): String {
        for (attempt in 0..maxRetries) {
            val response = try {
                execute(request)
            } catch (e: IOException) {
                if (attempt == maxRetries) throw e
                wait(backoff(attempt))
                continue
            }
            if (response.code in 200..299) return response.body
            val retryAfter = response.retryAfter?.let { value ->
                value.toLongOrNull()?.takeIf { it >= 0 }?.let { seconds ->
                    seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
                } ?: runCatching {
                    (ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME)
                        .toInstant().toEpochMilli() - nowMs()).coerceAtLeast(0)
                }.getOrNull()
            }
            val failure = HttpFailure(response.code, retryAfter)
            // Long server delays defer the run instead of holding it beyond its budget.
            if (!failure.isTransient || attempt == maxRetries || (retryAfter ?: 0) > 60_000) throw failure
            wait(if (response.code == 429) maxOf(retryAfter ?: 3_000, backoff(attempt)) else backoff(attempt))
        }
        error("Unreachable retry state")
    }

    private fun backoff(attempt: Int) = (initialBackoffMs * (1L shl attempt.coerceAtMost(20))).coerceAtMost(maxBackoffMs)

    private suspend fun execute(request: Request): Payload = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    val payload = response.use {
                        Payload(it.code, it.body?.string() ?: "", it.header("Retry-After"))
                    }
                    continuation.resume(payload)
                } catch (e: Exception) {
                    continuation.resumeWithException(e)
                }
            }
        })
    }
}
