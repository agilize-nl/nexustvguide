package com.nexustvguide.app

import android.app.Application
import com.jakewharton.threetenabp.AndroidThreeTen

class NexusTVGuideApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialiseer ThreeTenABP voor tijdzone- en datumondersteuning op Android
        AndroidThreeTen.init(this)
    }
}
