package com.nexustvguide.app.update

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.IOException

/**
 * Bewijst dat [UpdateRedirectInterceptor] elke hop ziet, niet alleen de eerste request.
 * Dat is de kern van het beleid: OkHttp volgt redirects nu wel, dus een niet-gecontroleerde
 * hop zou een release-host een willekeurige downloadbron laten aanwijzen.
 */
class UpdateRedirectInterceptorTest {

    private lateinit var server: MockWebServer

    @Before
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun teardown() {
        server.shutdown()
    }

    private fun clientWith(allowlist: Set<String>, allowInsecure: Boolean): OkHttpClient =
        OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor(
                UpdateRedirectInterceptor(
                    allowlistProvider = { allowlist },
                    allowInsecureProvider = { allowInsecure }
                )
            )
            .build()

    @Test
    fun `allows a request to a permitted host`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = clientWith(setOf(server.hostName), allowInsecure = true)
        val response = client.newCall(Request.Builder().url(server.url("/version.json")).build()).execute()

        assertEquals(200, response.code)
        response.close()
    }

    @Test
    fun `rejects the initial request to a host outside the allowlist`() {
        val client = clientWith(setOf("github.com"), allowInsecure = true)

        try {
            client.newCall(Request.Builder().url(server.url("/x.apk")).build()).execute()
            fail("Verwachtte een IOException voor een niet-toegestane host")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("geweigerd"))
        }
    }

    @Test
    fun `rejects a redirect hop that leaves the allowlist`() {
        // De eerste host is toegestaan, de redirect-doelhost niet. Zonder controle op de
        // tweede hop zou de download gewoon doorlopen.
        server.enqueue(
            MockResponse()
                .setResponseCode(302)
                .setHeader("Location", "http://evil.example.com/payload.apk")
        )

        val client = clientWith(setOf(server.hostName), allowInsecure = true)

        try {
            client.newCall(Request.Builder().url(server.url("/x.apk")).build()).execute()
            fail("Verwachtte een IOException op de redirect-hop")
        } catch (e: IOException) {
            assertTrue(
                "Onverwachte melding: ${e.message}",
                e.message!!.contains("evil.example.com")
            )
        }
    }

    @Test
    fun `rejects an http hop when the channel requires https`() {
        // Downgrade-bescherming: followSslRedirects staat https -> http toe, dus het beleid
        // moet die hop weigeren zolang het kanaal zelf https is.
        val client = clientWith(setOf(server.hostName), allowInsecure = false)

        try {
            client.newCall(Request.Builder().url(server.url("/x.apk")).build()).execute()
            fail("Verwachtte een IOException voor een http-hop op een https-kanaal")
        } catch (e: IOException) {
            assertTrue("Onverwachte melding: ${e.message}", e.message!!.contains("HTTPS"))
        }
    }
}
