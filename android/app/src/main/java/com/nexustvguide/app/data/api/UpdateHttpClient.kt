package com.nexustvguide.app.data.api

import android.os.Build
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.update.UpdateOriginPolicy
import com.nexustvguide.app.update.UpdateRedirectInterceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object UpdateHttpClient {

    private var okHttpClient: OkHttpClient? = null
    private var updateApiService: UpdateApiService? = null
    private var currentBaseUrl: String? = null

    /** Hosts die naast de eigen origin een APK mogen leveren, uit `UPDATE_HOST_ALLOWLIST`. */
    val allowlist: Set<String> by lazy {
        UpdateOriginPolicy.parseAllowlist(BuildConfig.UPDATE_HOST_ALLOWLIST)
    }

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
        // De emulator-omleiding geldt alleen voor een lokale backend; een extern
        // updatekanaal is vanaf de emulator gewoon bereikbaar.
        return if (isEmulator() && !BuildConfig.UPDATE_BASE_URL.startsWith("https://")) {
            "http://10.0.2.2:3000/"
        } else {
            BuildConfig.UPDATE_BASE_URL
        }
    }

    /**
     * Of onversleuteld verkeer is toegestaan. Alleen waar het updatekanaal zelf nog http is
     * (de LAN-backend of de emulator); een https-kanaal dwingt https af op elke hop.
     */
    fun allowsInsecureTransport(): Boolean = !getUpdateBaseUrl().startsWith("https://")

    fun getOkHttpClient(): OkHttpClient {
        if (okHttpClient == null) {
            val logging = HttpLoggingInterceptor().apply {
                level = if (BuildConfig.DEBUG) HttpLoggingInterceptor.Level.BASIC else HttpLoggingInterceptor.Level.NONE
            }

            okHttpClient = OkHttpClient.Builder()
                // Release-hosts leiden een asset door naar een aparte CDN-host. Redirects
                // worden gevolgd, maar elke hop wordt tegen de allowlist gecontroleerd door
                // UpdateRedirectInterceptor; zonder die controle zou dit een open doorgeefluik zijn.
                .followRedirects(true)
                .followSslRedirects(true)
                .addNetworkInterceptor(
                    UpdateRedirectInterceptor(
                        allowlistProvider = { allowlist },
                        allowInsecureProvider = { allowsInsecureTransport() }
                    )
                )
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
