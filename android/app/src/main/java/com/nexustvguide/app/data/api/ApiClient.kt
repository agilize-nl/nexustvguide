package com.nexustvguide.app.data.api

import android.content.Context
import android.os.Build
import com.nexustvguide.app.BuildConfig
import okhttp3.Cache
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

object ApiClient {

    private var apiService: GuideApiService? = null
    private var currentBaseUrl: String? = null

    private const val PREFS_NAME = "nexus_tv_guide_settings"
    private const val KEY_BASE_URL = "base_url"

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu"))
    }

    fun getBaseUrl(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_BASE_URL, null)
        if (saved != null) return saved

        return if (isEmulator()) {
            "http://10.0.2.2:3000/"
        } else {
            BuildConfig.DEFAULT_BASE_URL
        }
    }

    fun setBaseUrl(context: Context, url: String) {
        val formattedUrl = if (url.endsWith("/")) url else "$url/"
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BASE_URL, formattedUrl)
            .apply()
        apiService = null
        currentBaseUrl = formattedUrl
    }

    fun getService(context: Context, customBaseUrl: String? = null): GuideApiService {
        val targetUrl = customBaseUrl ?: getBaseUrl(context)
        if (apiService == null || currentBaseUrl != targetUrl) {
            val cacheDir = File(context.cacheDir, "http_cache")
            val cache = Cache(cacheDir, 20L * 1024L * 1024L) // 20 MB cache

            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            }

            val okHttpClient = OkHttpClient.Builder()
                .cache(cache)
                .addInterceptor(logging)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()

            val retrofit = Retrofit.Builder()
                .baseUrl(targetUrl)
                .client(okHttpClient)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            apiService = retrofit.create(GuideApiService::class.java)
            currentBaseUrl = targetUrl
        }
        return apiService!!
    }
}
