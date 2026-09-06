package com.nexustvguide.app.core.refresh

import com.nexustvguide.app.core.domain.Channel
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

data class RefreshStatus(
    val running: Boolean = false,
    val result: RefreshResult? = null,
    val error: String? = null
)

/** Application-owned work: all callers await the same cycle; leaving a screen only cancels its wait. */
class RefreshCoordinator(
    private val refreshEngine: RefreshEngine,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val cycleBudgetMs: Long = 5 * 60 * 1000L,
    private val onStatus: (RefreshStatus) -> Unit = {},
    private val minIntervalMs: Long = 60_000,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private var lastFinishedAt = Long.MIN_VALUE
    private var inFlight: Deferred<RefreshResult>? = null
    private val mutableStatus = MutableStateFlow(RefreshStatus())
    val status: StateFlow<RefreshStatus> = mutableStatus
    val lastResult: RefreshResult? get() = status.value.result
    fun isRefreshing(): Boolean = status.value.running

    @Synchronized
    fun request(activeChannels: List<Channel>, priorityDate: String? = null): Deferred<RefreshResult> {
        inFlight?.takeIf {
            !it.isCancelled && (!it.isCompleted || (lastFinishedAt != Long.MIN_VALUE && nowMs() - lastFinishedAt < minIntervalMs))
        }?.let { return it }
        val task = scope.async(start = CoroutineStart.LAZY) {
            publish(RefreshStatus(running = true, result = lastResult))
            try {
                val result = withTimeout(cycleBudgetMs) { refreshEngine.refreshAll(activeChannels, priorityDate) }
                publish(RefreshStatus(result = result))
                result
            } catch (e: Exception) {
                publish(RefreshStatus(result = lastResult, error = e.message ?: e.javaClass.simpleName))
                throw e
            } finally {
                lastFinishedAt = nowMs()
            }
        }
        inFlight = task
        task.start()
        return task
    }

    suspend fun refresh(activeChannels: List<Channel>, priorityDate: String? = null): RefreshResult =
        request(activeChannels, priorityDate).await()

    @Synchronized
    fun cancel() { inFlight?.cancel() }

    private fun publish(value: RefreshStatus) {
        mutableStatus.value = value
        onStatus(value)
    }
}
