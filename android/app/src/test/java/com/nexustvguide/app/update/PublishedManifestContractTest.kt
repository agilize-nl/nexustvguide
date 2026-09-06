package com.nexustvguide.app.update

import com.google.gson.Gson
import com.nexustvguide.app.data.model.AppUpdateDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contracttest tussen tools/publish-release.mjs en de app.
 *
 * Het JSON hieronder is letterlijk de uitvoer van het release-kanaal. Zo valt een
 * schemawijziging in de publicatietooling hier om, in plaats van pas op een Shield
 * die geen update meer kan ophalen.
 */
class PublishedManifestContractTest {

    private val publishedManifest = """
        {
          "schemaVersion": 1,
          "applicationId": "com.nexustvguide.app",
          "versionCode": 24,
          "versionName": "0.8.23",
          "releaseNotes": "Eindtest",
          "sha256": "9de6844befd596c58649d258971729d0e6c13e7ff674fff1bb14dda604dfcdd6",
          "fileSizeBytes": 2762972,
          "publishedAt": "2026-09-06T22:21:13.290Z",
          "downloadUrl": "https://github.com/djawiz/NexusTVGuide/releases/download/v0.8.23/nexus-tv-guide-0.8.23.apk"
        }
    """.trimIndent()

    @Test
    fun `manifest produced by the release channel passes validation`() {
        val dto = Gson().fromJson(publishedManifest, AppUpdateDto::class.java)

        val result = UpdateMetadataValidator.validate(
            dto = dto,
            updateBaseUrl = "https://github.com/djawiz/NexusTVGuide/releases/download/v0.8.23/",
            allowlist = setOf("github.com", "objects.githubusercontent.com"),
            allowInsecure = false
        )

        assertTrue("Verwacht Success, kreeg: $result", result is MetadataValidationResult.Success)
        val meta = (result as MetadataValidationResult.Success).metadata
        assertEquals(24L, meta.versionCode)
        assertEquals(
            "https://github.com/djawiz/NexusTVGuide/releases/download/v0.8.23/nexus-tv-guide-0.8.23.apk",
            meta.downloadUrl
        )
    }

    @Test
    fun `LAN manifest keeps passing validation`() {
        // Regressiebewaking: het oude backend-formaat mag niet stilzwijgend breken.
        val lanManifest = publishedManifest.replace(
            "\"downloadUrl\": \"https://github.com/djawiz/NexusTVGuide/releases/download/v0.8.23/nexus-tv-guide-0.8.23.apk\"",
            "\"downloadPath\": \"/api/v1/app/download/nexus-tv-guide-0.8.23.apk\""
        )
        val dto = Gson().fromJson(lanManifest, AppUpdateDto::class.java)

        val result = UpdateMetadataValidator.validate(
            dto = dto,
            updateBaseUrl = "http://192.168.2.171:3000/",
            allowlist = emptySet(),
            allowInsecure = true
        )

        assertTrue("Verwacht Success, kreeg: $result", result is MetadataValidationResult.Success)
        assertEquals(
            "http://192.168.2.171:3000/api/v1/app/download/nexus-tv-guide-0.8.23.apk",
            (result as MetadataValidationResult.Success).metadata.downloadUrl
        )
    }
}
