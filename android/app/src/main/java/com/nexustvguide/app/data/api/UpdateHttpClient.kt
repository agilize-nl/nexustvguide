package com.nexustvguide.app.data.api

import android.content.Context
import android.os.Build
import com.nexustvguide.app.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object UpdateHttpClient {

    private var okHttpClient: OkHttpClient? = null
    private var updateApiService: UpdateApiService? = null
    private var currentBaseUrl: String? = null

    private fun isEmulator(): Boolean {
        return (Build.FINGERPRINT.startsWith("generic")
                || Build.FINGERPRINT.startsWith("unknown")
                || Build.MODEL.contains("google_sdk")
                || Build.MODEL.contains("Emulator")
                || Build.MODEL.contains("Android SDK built for x86")
                || Build.HARDWARE.contains("goldfish")
                || Build.HARDWARE.contains("ranchu"))
    }

    fun getUpdateBaseUrl(): String {
        return if (isEmulator()) {
            "http://10.0.2.2:3000/"
        } else {
            BuildConfig.UPDATE_BASE_URL
        }
    }

    fun getOkHttpClient(): OkHttpClient {
        if (okHttpClient == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            }

            okHttpClient = OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .build()
        }
        return okHttpClient!!
    }

    fun getService(customBaseUrl: String? = null): UpdateApiService {
        val targetUrl = customBaseUrl ?: getUpdateBaseUrl()
        if (updateApiService == null || currentBaseUrl != targetUrl) {
            val client = getOkHttpClient()
            val retrofit = Retrofit.Builder()
                .baseUrl(targetUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()

            updateApiService = retrofit.create(UpdateApiService::class.java)
            currentBaseUrl = targetUrl
        }
        return updateApiService!!
    }
}
