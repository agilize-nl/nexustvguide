package com.nexustvguide.app.data.repository

import android.content.Context
import com.nexustvguide.app.BuildConfig

object GuideRepositoryProvider {
    private const val PREFS_NAME = "nexus_guide_settings"
    private const val KEY_GUIDE_SOURCE = "guide_source"

    @Volatile private var localRepo: LocalGuideRepository? = null
    @Volatile private var remoteRepo: RemoteGuideRepository? = null

    fun getGuideSource(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_GUIDE_SOURCE, BuildConfig.GUIDE_SOURCE) ?: "LOCAL"
    }

    fun setGuideSource(context: Context, source: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_GUIDE_SOURCE, source).apply()
    }

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
