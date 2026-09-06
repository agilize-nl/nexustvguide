package com.nexustvguide.app.update

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Bepaalt welke hosts een update-artefact mogen leveren.
 *
 * Achtergrond: bij een LAN-backend lagen metadata en APK op dezelfde origin, waardoor een
 * strikte same-origin-check volstond. Release-hosting (GitHub, Forgejo, Gitea) serveert de
 * APK echter vanaf een aparte asset-host — bij GitHub is dat een redirect naar
 * `objects.githubusercontent.com`. Daarom vervangt een expliciete allowlist de
 * host-gelijkheidscontrole.
 *
 * De allowlist is een aanvullende beperking, geen vervanging van de integriteitscontrole:
 * [Sha256Checksum] en [ApkVerifier] blijven de harde garantie dat er precies de verwachte,
 * met de eigen sleutel ondertekende APK geïnstalleerd wordt.
 */
object UpdateOriginPolicy {

    /**
     * Resultaat van een hostcontrole. [Rejected] draagt een leesbare reden zodat de
     * updater kan tonen waarom een bron geweigerd is.
     */
    sealed class Result {
        object Allowed : Result()
        data class Rejected(val reason: String) : Result()
    }

    /**
     * Splitst een door de build meegegeven allowlist ("a.com,b.com") in genormaliseerde hosts.
     * Lege elementen en witruimte worden genegeerd, zodat een trailing komma geen lege host oplevert.
     */
    fun parseAllowlist(raw: String): Set<String> =
        raw.split(',')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

    /**
     * Controleert of [url] als updatebron gebruikt mag worden.
     *
     * @param allowInsecure staat http toe voor een LAN-backend of emulator. Alleen bedoeld
     *   voor debug-builds en de bestaande `.171`-server; op een publiek kanaal hoort dit uit.
     * @param sameOriginWith optionele basis-URL; die origin is altijd toegestaan, zodat een
     *   zelfgehoste server zonder allowlist-configuratie blijft werken.
     */
    fun check(
        url: HttpUrl,
        allowlist: Set<String>,
        allowInsecure: Boolean,
        sameOriginWith: HttpUrl? = null
    ): Result {
        if (!url.isHttps && !allowInsecure) {
            return Result.Rejected("Updatebron moet HTTPS gebruiken: '$url'")
        }

        if (sameOriginWith != null &&
            url.scheme == sameOriginWith.scheme &&
            url.host == sameOriginWith.host &&
            url.port == sameOriginWith.port
        ) {
            return Result.Allowed
        }

        val host = url.host.lowercase()
        if (allowlist.contains(host)) {
            return Result.Allowed
        }

        return Result.Rejected(
            "Host '$host' staat niet op de lijst met toegestane updatebronnen"
        )
    }

    /**
     * Stringvariant voor aanroepers die nog geen geparste [HttpUrl] hebben.
     */
    fun check(
        url: String,
        allowlist: Set<String>,
        allowInsecure: Boolean,
        sameOriginWith: HttpUrl? = null
    ): Result {
        val parsed = url.toHttpUrlOrNull()
            ?: return Result.Rejected("Ongeldige URL: '$url'")
        return check(parsed, allowlist, allowInsecure, sameOriginWith)
    }
}
