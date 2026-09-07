package com.nexustvguide.app.update

import com.nexustvguide.app.data.api.UpdateHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Legt het noodkanaal vast: naast het geconfigureerde updatekanaal moet de LAN-server
 * bereikbaar blijven, zonder dat die uitzondering het publieke kanaal verzwakt.
 */
@RunWith(RobolectricTestRunner::class)
class UpdateChannelTest {

    @Test
    fun `LAN-kanaal wijst naar de tvguide-api met het legacy manifestpad`() {
        val lan = UpdateChannel.lanFallback()
        assertEquals("http://192.168.2.171:3000/", lan.baseUrl)
        assertEquals("api/v1/app/version", lan.manifestPath)
    }

    @Test
    fun `LAN-kanaal staat http toe, een https-primary niet`() {
        assertTrue(UpdateChannel.lanFallback().allowInsecure)
        assertFalse(UpdateChannel.primary("https://github.com/x/y/releases/latest/download/").allowInsecure)
    }

    @Test
    fun `een http-primary mag wel onversleuteld, want dat is de LAN-build zelf`() {
        assertTrue(UpdateChannel.primary("http://192.168.2.171:3000/").allowInsecure)
    }

    @Test
    fun `LAN blijft toegestaan via same-origin, ook zonder allowlist`() {
        val lan = UpdateChannel.lanFallback()
        val result = UpdateOriginPolicy.check(
            url = "http://192.168.2.171:3000/api/v1/app/download/nexus-tv-guide-1.0.0.apk",
            allowlist = lan.allowlist,
            allowInsecure = lan.allowInsecure,
            sameOriginWith = lan.baseUrl.toHttpUrl()
        )
        assertTrue("LAN-APK op de eigen origin moet zijn toegestaan", result is UpdateOriginPolicy.Result.Allowed)
    }

    @Test
    fun `het publieke kanaal weigert een http-bron, ondanks het LAN-noodkanaal`() {
        val primary = UpdateChannel.primary("https://github.com/x/y/releases/latest/download/")
        val result = UpdateOriginPolicy.check(
            url = "http://evil.example/pwned.apk",
            allowlist = primary.allowlist,
            allowInsecure = primary.allowInsecure,
            sameOriginWith = primary.baseUrl.toHttpUrl()
        )
        assertTrue("http mag nooit via het https-kanaal binnenkomen", result is UpdateOriginPolicy.Result.Rejected)
    }

    @Test
    fun `channels begint altijd met het geconfigureerde kanaal`() {
        val ids = UpdateHttpClient.channels().map { it.id }
        assertEquals(UpdateChannel.ID_PRIMARY, ids.first())
    }

    @Test
    fun `een https-build krijgt het LAN erbij als tweede kanaal`() {
        // De testbuild draait zelf op het LAN; dan valt er niets terug te vallen en blijft
        // het bij een kanaal. Het interessante geval is de release-build (GitHub), waar het
        // LAN er als noodkanaal achter hoort te staan. Dat is de dedupe-regel in channels().
        val primary = UpdateChannel.primary("https://github.com/x/y/releases/latest/download/")
        val lan = UpdateChannel.lanFallback()
        assertTrue(
            "een https-primary en het LAN zijn verschillende bronnen",
            primary.baseUrl.trimEnd('/') != lan.baseUrl.trimEnd('/')
        )
    }

    @Test
    fun `een LAN-build levert geen dubbel kanaal op`() {
        val primary = UpdateChannel.primary(UpdateChannel.LAN_BASE_URL)
        val lan = UpdateChannel.lanFallback()
        assertEquals(
            "identieke bron mag niet twee keer geprobeerd worden",
            primary.baseUrl.trimEnd('/'), lan.baseUrl.trimEnd('/')
        )
    }
}
