package com.nexustvguide.app.core.refresh

import com.nexustvguide.app.core.domain.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class RefreshCoordinator(
    private val refreshEngine: RefreshEngine
) {
    private val mutex = Mutex()
    @Volatile private var isRunning = false
    @Volatile var lastResult: RefreshResult? = null
        private set

    fun isRefreshing(): Boolean = isRunning

    suspend fun refresh(
        activeChannels: List<Channel>,
        priorityDate: String? = null
    ): RefreshResult {
        mutex.withLock {
            isRunning = true
            try {
                val result = refreshEngine.refreshAll(activeChannels, priorityDate)
                lastResult = result
                return result
            } finally {
                isRunning = false
            }
        }
    }
}
