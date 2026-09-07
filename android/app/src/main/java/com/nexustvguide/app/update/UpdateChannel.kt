package com.nexustvguide.app.update

import com.nexustvguide.app.BuildConfig

/**
 * Een updatebron: waar het manifest staat, welke hosts het APK mogen leveren en of
 * onversleuteld verkeer daarbij is toegestaan.
 *
 * De app kent twee kanalen. [primary] is het kanaal waarmee de build is geconfigureerd
 * (normaal GitHub Releases). [lanFallback] is de zelfgehoste tvguide-api op het LAN, die
 * als noodkanaal blijft bestaan: gaat het publieke kanaal onderuit, of publiceert het een
 * kapotte versie, dan is een LAN-server de enige manier om een Shield nog te bereiken
 * zonder fysieke toegang. Precies dat scenario deed zich voor bij 0.8.24.
 *
 * Elk kanaal draagt zijn eigen [allowInsecure]: het LAN draait op http, maar dat mag nooit
 * betekenen dat het publieke https-kanaal ook naar http mag terugvallen. Vandaar dat deze
 * vlag per kanaal geldt en niet, zoals eerder, globaal uit de base-URL wordt afgeleid.
 */
data class UpdateChannel(
    val id: String,
    val label: String,
    val baseUrl: String,
    val manifestPath: String,
    val allowlist: Set<String>,
    val allowInsecure: Boolean
) {
    companion object {
        const val ID_PRIMARY = "primary"
        const val ID_LAN = "lan"

        /** Vaste LAN-noodbron; bewust hardcoded zodat hij ook werkt als de build op GitHub staat. */
        const val LAN_BASE_URL = "http://192.168.2.171:3000/"
        const val LAN_MANIFEST_PATH = "api/v1/app/version"

        /**
         * Het kanaal uit de buildconfiguratie. Op een LAN-build is dit http; dan mag
         * onversleuteld verkeer, anders niet.
         */
        fun primary(baseUrl: String = BuildConfig.UPDATE_BASE_URL): UpdateChannel = UpdateChannel(
            id = ID_PRIMARY,
            label = "Internet",
            baseUrl = baseUrl,
            manifestPath = BuildConfig.UPDATE_MANIFEST_PATH,
            allowlist = UpdateOriginPolicy.parseAllowlist(BuildConfig.UPDATE_HOST_ALLOWLIST),
            allowInsecure = !baseUrl.startsWith("https://")
        )

        /**
         * De LAN-server. Serveert het APK vanaf zijn eigen origin, dus een allowlist is hier
         * niet nodig: de same-origin-regel in [UpdateOriginPolicy] dekt het af.
         */
        fun lanFallback(baseUrl: String = LAN_BASE_URL): UpdateChannel = UpdateChannel(
            id = ID_LAN,
            label = "Lokaal netwerk",
            baseUrl = baseUrl,
            manifestPath = LAN_MANIFEST_PATH,
            allowlist = emptySet(),
            allowInsecure = true
        )
    }
}
