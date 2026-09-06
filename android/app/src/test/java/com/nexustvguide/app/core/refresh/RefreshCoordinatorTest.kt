package com.nexustvguide.app.core.refresh

import com.nexustvguide.app.core.domain.Channel
import com.nexustvguide.app.core.domain.DaySnapshot
import com.nexustvguide.app.core.nlziet.EpgSource
import com.nexustvguide.app.core.nlziet.NlzietEpgMatcher
import com.nexustvguide.app.core.nlziet.NlzietEpgResponse
import com.nexustvguide.app.core.source.tvgids.ProgrammeSource
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.threeten.bp.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Plan §6.1: one shared cycle for Worker, app start, day switch and prefetch — callers
 * await the same run instead of queueing extra ones, and leaving a screen only cancels
 * that caller's wait.
 */
class RefreshCoordinatorTest {

    private val fixedNow = Instant.parse("2026-08-30T10:00:00Z")
    private val channel = Channel("npo1", "1", "NPO 1", inNlziet = true, nlzietChannelId = "npo1", sortOrder = 1)

    private class CountingStorage : SnapshotStorage {
        override suspend fun getExistingProgrammeCount(date: String): Int? = null
        override suspend fun getSnapshot(date: String): DaySnapshot? = null
        override suspend fun saveDaySnapshot(snapshot: DaySnapshot) {}
        override suspend fun cleanOldSnapshots(minAllowedDate: String) {}
    }

    /** Blocks the cycle until released, so overlapping callers are provably concurrent. */
    private class GatedSource(private val gate: CompletableDeferred<Unit>) : ProgrammeSource {
        val calls = AtomicInteger(0)
        override suspend fun fetchPrograms(dayOffset: Int, channelSourceIds: List<String>?): String {
            calls.incrementAndGet()
            gate.await()
            return """{"version":"1.0","data":{"1":{"ch_id":"1","prog":[
                {"s":"1788079200","e":"1788082800","db_id":"1","title":"Prog 1"}]}}}"""
        }
    }

    private object EmptyEpg : EpgSource {
        override fun isDateInEpgWindow(dateStr: String, todayStr: String?) = false
        override suspend fun fetchEpg(dateStr: String, channelIds: List<String>, todayStr: String?) =
            NlzietEpgResponse(emptyList())
    }

    private fun coordinator(source: ProgrammeSource, scope: CoroutineScope) = RefreshCoordinator(
        RefreshEngine(
            tvgidsClient = source,
            epgClient = EmptyEpg,
            epgMatcher = NlzietEpgMatcher,
            storage = CountingStorage(),
            nowFn = { fixedNow }
        ),
        scope = scope,
        minIntervalMs = 0
    )

    @Test
    fun `concurrent callers share a single refresh cycle`() : Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = coordinator(source, scope)

        // Three callers (app start, prefetch, Worker) request while the cycle is in flight.
        val first = coordinator.request(listOf(channel))
        val second = coordinator.request(listOf(channel))
        val third = coordinator.request(listOf(channel))

        assertSame("all callers must await the same cycle", first, second)
        assertSame("all callers must await the same cycle", first, third)

        gate.complete(Unit)
        val results = listOf(first, second, third).awaitAll()

        assertEquals(1, results.map { it }.distinct().size)
        // 16 provider offsets fetched once, not once per caller.
        assertEquals(16, source.calls.get())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `a cancelled waiter does not abort the shared cycle`() : Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val source = GatedSource(gate)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = coordinator(source, scope)

        val shared = coordinator.request(listOf(channel))
        // A screen leaves and abandons its wait.
        val waiter = async { shared.await() }
        waiter.cancel()

        gate.complete(Unit)
        val result = shared.await()

        assertTrue(shared.isCompleted)
        assertTrue(!shared.isCancelled)
        assertEquals(16, source.calls.get())
        assertTrue(result.successfulDays.isNotEmpty())
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    @Test
    fun `cancelling the owner stops the cycle`() : Unit = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val coordinator = coordinator(GatedSource(gate), scope)

        val shared = coordinator.request(listOf(channel))
        while (!coordinator.isRefreshing()) Thread.sleep(5)
        coordinator.cancel()

        try {
            shared.await()
        } catch (_: Exception) {
            // Cancellation surfaces to the owner rather than being swallowed.
        }
        assertTrue(shared.isCancelled)
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }
}
