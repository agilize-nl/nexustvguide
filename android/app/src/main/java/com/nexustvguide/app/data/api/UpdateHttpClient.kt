package com.nexustvguide.app.data.api

import android.os.Build
import com.nexustvguide.app.BuildConfig
import com.nexustvguide.app.update.UpdateChannel
import com.nexustvguide.app.update.UpdateOriginPolicy
import com.nexustvguide.app.update.UpdateRedirectInterceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

object UpdateHttpClient {

    private var okHttpClient: OkHttpClient? = null
    private val serviceCache = mutableMapOf<String, UpdateApiService>()

    /**
     * Het kanaal dat de gedeelde OkHttp-client op dit moment bedient. De
     * redirect-interceptor leest hieruit welke allowlist en welk transport gelden, zodat
     * één client beide kanalen kan bedienen zonder dat het https-kanaal ooit de
     * http-toestemming van het LAN-kanaal overneemt.
     */
    @Volatile
    private var activeChannel: UpdateChannel? = null

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

    /** Het kanaal uit de buildconfiguratie, met de emulator-omleiding toegepast. */
    fun primaryChannel(): UpdateChannel = UpdateChannel.primary(getUpdateBaseUrl())

    /** Het LAN-noodkanaal. */
    fun lanChannel(): UpdateChannel = UpdateChannel.lanFallback()

    /**
     * De kanalen die een updatecontrole achtereenvolgens mag proberen. Staat de build al op
     * het LAN, dan is er niets om op terug te vallen en blijft het bij dat ene kanaal.
     */
    fun channels(): List<UpdateChannel> {
        val primary = primaryChannel()
        val lan = lanChannel()
        return if (primary.baseUrl.trimEnd('/') == lan.baseUrl.trimEnd('/')) {
            listOf(primary)
        } else {
            listOf(primary, lan)
        }
    }

    /** Stelt het kanaal in dat de gedeelde client bedient; zie [activeChannel]. */
    fun setActiveChannel(channel: UpdateChannel) {
        activeChannel = channel
    }

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
                        allowlistProvider = { activeChannel?.allowlist ?: allowlist },
                        allowInsecureProvider = { activeChannel?.allowInsecure ?: allowsInsecureTransport() }
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
        return synchronized(serviceCache) {
            serviceCache.getOrPut(targetUrl) {
                Retrofit.Builder()
                    .baseUrl(targetUrl)
                    .client(getOkHttpClient())
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                    .create(UpdateApiService::class.java)
            }
        }
    }
}
