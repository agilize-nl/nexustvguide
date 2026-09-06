package com.nexustvguide.app

import android.app.Application
import com.jakewharton.threetenabp.AndroidThreeTen
import com.nexustvguide.app.work.GuideRefreshWorker

class NexusTVGuideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialiseer ThreeTenABP voor tijdzone- en datumondersteuning op Android
        AndroidThreeTen.init(this)

        // Plan periodieke achtergrondverversing via WorkManager
        try { GuideRefreshWorker.schedule(this) } catch (_: Exception) {}
    }
}
