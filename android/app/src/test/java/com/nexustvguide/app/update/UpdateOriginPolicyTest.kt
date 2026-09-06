package com.nexustvguide.app.update

import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateOriginPolicyTest {

    private val allowlist = setOf("github.com", "objects.githubusercontent.com")

    private fun check(
        url: String,
        allowlist: Set<String> = this.allowlist,
        allowInsecure: Boolean = false,
        sameOriginWith: String? = null
    ) = UpdateOriginPolicy.check(
        url = url.toHttpUrl(),
        allowlist = allowlist,
        allowInsecure = allowInsecure,
        sameOriginWith = sameOriginWith?.toHttpUrl()
    )

    @Test
    fun `parses allowlist and ignores whitespace and empty entries`() {
        val parsed = UpdateOriginPolicy.parseAllowlist(" github.com , objects.githubusercontent.com ,, ")
        assertEquals(setOf("github.com", "objects.githubusercontent.com"), parsed)
    }

    @Test
    fun `parses empty allowlist to an empty set`() {
        assertTrue(UpdateOriginPolicy.parseAllowlist("").isEmpty())
        assertTrue(UpdateOriginPolicy.parseAllowlist("   ").isEmpty())
    }

    @Test
    fun `normalises host casing on both sides`() {
        val parsed = UpdateOriginPolicy.parseAllowlist("GitHub.com")
        assertTrue(check("https://GITHUB.com/a.apk", allowlist = parsed) is UpdateOriginPolicy.Result.Allowed)
    }

    @Test
    fun `allows an allowlisted https host`() {
        assertTrue(check("https://objects.githubusercontent.com/x.apk") is UpdateOriginPolicy.Result.Allowed)
    }

    @Test
    fun `rejects a host outside the allowlist`() {
        assertTrue(check("https://evil.example.com/x.apk") is UpdateOriginPolicy.Result.Rejected)
    }

    @Test
    fun `rejects http when insecure transport is not permitted`() {
        assertTrue(check("http://github.com/x.apk") is UpdateOriginPolicy.Result.Rejected)
    }

    @Test
    fun `allows http for the LAN backend when insecure transport is permitted`() {
        val result = check(
            "http://192.168.2.171:3000/api/v1/app/download/x.apk",
            allowlist = emptySet(),
            allowInsecure = true,
            sameOriginWith = "http://192.168.2.171:3000/"
        )
        assertTrue(result is UpdateOriginPolicy.Result.Allowed)
    }

    @Test
    fun `same origin is allowed without an allowlist entry`() {
        val result = check(
            "https://releases.example.org/apk/x.apk",
            allowlist = emptySet(),
            sameOriginWith = "https://releases.example.org/"
        )
        assertTrue(result is UpdateOriginPolicy.Result.Allowed)
    }

    @Test
    fun `same origin does not match across differing ports`() {
        val result = check(
            "https://releases.example.org:8443/x.apk",
            allowlist = emptySet(),
            sameOriginWith = "https://releases.example.org/"
        )
        assertTrue(result is UpdateOriginPolicy.Result.Rejected)
    }

    @Test
    fun `subdomains of an allowlisted host are not implicitly trusted`() {
        // Alleen exacte hostmatches tellen; anders zou een overgenomen subdomein volstaan.
        assertTrue(check("https://evil.github.com.attacker.net/x.apk") is UpdateOriginPolicy.Result.Rejected)
    }

    @Test
    fun `rejects an unparseable url via the string overload`() {
        val result = UpdateOriginPolicy.check("not a url", allowlist, allowInsecure = false)
        assertTrue(result is UpdateOriginPolicy.Result.Rejected)
    }
}
