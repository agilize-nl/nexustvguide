package com.nexustvguide.app.data.repository

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.nexustvguide.app.data.api.ApiClient
import com.nexustvguide.app.data.api.GuideApiService
import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.GuideResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class GuideRepository(private val context: Context, private val api: GuideApiService = ApiClient.getService(context)) {

    private val gson = Gson()
    private val diskCacheDir = File(context.cacheDir, "guide_snapshots").apply { mkdirs() }
    private val etagPrefs = context.getSharedPreferences("guide_etags", Context.MODE_PRIVATE)

    companion object {
        private const val TAG = "GuideRepository"
    }

    suspend fun getChannels(): List<ChannelDto> = withContext(Dispatchers.IO) {
        try {
            val channels = api.getChannels()
            saveChannelsToDisk(channels)
            channels
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch channels from network, loading disk cache", e)
            loadChannelsFromDisk() ?: emptyList()
        }
    }

    suspend fun getGuideForDate(date: String): GuideResponseDto? = withContext(Dispatchers.IO) {
        val cachedEtag = etagPrefs.getString("etag_$date", null)
        val diskSnapshot = loadSnapshotFromDisk(date)

        try {
            val response = api.getGuideForDate(date, cachedEtag)

            when (response.code()) {
                200 -> {
                    val body = response.body()
                    if (body != null) {
                        val etag = response.headers()["ETag"]
                        if (etag != null) {
                            etagPrefs.edit().putString("etag_$date", etag).apply()
                        }
                        saveSnapshotToDisk(date, body)
                        body
                    } else {
                        diskSnapshot?.copy(meta = diskSnapshot.meta.copy(stale = true)) ?: diskSnapshot
                    }
                }
                304 -> {
                    // Not modified, use cached disk snapshot
                    diskSnapshot
                }
                else -> {
                    Log.w(TAG, "Guide API returned status: ${response.code()}")
                    diskSnapshot?.copy(meta = diskSnapshot.meta.copy(stale = true)) ?: diskSnapshot
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network error fetching guide for $date, using fallback disk cache", e)
            diskSnapshot?.copy(meta = diskSnapshot.meta.copy(stale = true)) ?: diskSnapshot
        }
    }

    private fun saveSnapshotToDisk(date: String, guide: GuideResponseDto) {
        try {
            val tmpFile = File(diskCacheDir, "guide_$date.json.tmp")
            val targetFile = File(diskCacheDir, "guide_$date.json")
            val json = gson.toJson(guide)
            tmpFile.writeText(json)
            tmpFile.renameTo(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save snapshot to disk for $date", e)
        }
    }

    private fun loadSnapshotFromDisk(date: String): GuideResponseDto? {
        return try {
            val file = File(diskCacheDir, "guide_$date.json")
            if (file.exists()) {
                val json = file.readText()
                gson.fromJson(json, GuideResponseDto::class.java)
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load snapshot from disk for $date", e)
            null
        }
    }

    private fun saveChannelsToDisk(channels: List<ChannelDto>) {
        try {
            val tmpFile = File(diskCacheDir, "channels.json.tmp")
            val targetFile = File(diskCacheDir, "channels.json")
            tmpFile.writeText(gson.toJson(channels))
            tmpFile.renameTo(targetFile)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save channels cache", e)
        }
    }

    private fun loadChannelsFromDisk(): List<ChannelDto>? {
        return try {
            val file = File(diskCacheDir, "channels.json")
            if (file.exists()) {
                val listType = object : com.google.gson.reflect.TypeToken<List<ChannelDto>>() {}.type
                gson.fromJson(file.readText(), listType)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}
