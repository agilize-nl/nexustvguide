package com.nexustvguide.app.data.repository

import android.content.Context
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.work.GuideRefreshWorker
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import android.content.SharedPreferences

object GuideRepositoryProvider {
    private const val PREFS_NAME = "nexus_guide_settings"
    private const val KEY_GUIDE_SOURCE = "guide_source"

    @Volatile private var localRepo: LocalGuideRepository? = null
    @Volatile private var remoteRepo: RemoteGuideRepository? = null

    fun getGuideSource(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GUIDE_SOURCE, BuildConfig.GUIDE_SOURCE)
            ?.takeIf { it == "LOCAL" || it == "REMOTE" } ?: BuildConfig.GUIDE_SOURCE
    }

    fun setGuideSource(context: Context, source: String) {
        require(source == "LOCAL" || source == "REMOTE")
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GUIDE_SOURCE, source).apply()
        if (source == "REMOTE") localRepo?.refreshCoordinator?.cancel()
        GuideRefreshWorker.schedule(context)
    }

    fun observeSource(context: Context) = callbackFlow {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_GUIDE_SOURCE) trySend(getGuideSource(context))
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(getGuideSource(context))
        awaitClose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }.distinctUntilChanged()

    fun getRepository(context: Context): GuideRepository {
        val source = getGuideSource(context)
        return if (source == "REMOTE") {
            remoteRepo ?: synchronized(this) {
                remoteRepo ?: RemoteGuideRepository(context.applicationContext).also { remoteRepo = it }
            }
        } else {
            localRepo ?: synchronized(this) {
                localRepo ?: LocalGuideRepository(context.applicationContext).also { localRepo = it }
            }
        }
    }

    fun getLocalRepository(context: Context): LocalGuideRepository {
        return localRepo ?: synchronized(this) {
            localRepo ?: LocalGuideRepository(context.applicationContext).also { localRepo = it }
        }
    }
}
