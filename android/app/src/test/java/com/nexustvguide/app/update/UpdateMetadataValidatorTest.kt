package com.nexustvguide.app.update

import com.nexustvguide.app.data.model.AppUpdateDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateMetadataValidatorTest {

    private val validBaseUrl = "http://192.168.2.171:3000/"

    private fun createValidDto(
        schemaVersion: Int = 1,
        applicationId: String = "com.nexustvguide.app",
        versionCode: Long = 2,
        versionName: String = "0.6.0",
        releaseNotes: String? = "Nieuwe functies",
        downloadPath: String? = "/api/v1/app/download/nexus-tv-guide-0.6.0.apk",
        downloadUrl: String? = null,
        sha256: String = "9a4f2f9f5b66f6b0f4f33dc51d5cf834d68f7f95d1a39de6fcd09b3a51fbe123",
        fileSizeBytes: Long = 18452100,
        publishedAt: String = "2026-08-31T12:00:00.000Z"
    ): AppUpdateDto {
        return AppUpdateDto(
            schemaVersion = schemaVersion,
            applicationId = applicationId,
            versionCode = versionCode,
            versionName = versionName,
            releaseNotes = releaseNotes,
            downloadPath = downloadPath,
            downloadUrl = downloadUrl,
            sha256 = sha256,
            fileSizeBytes = fileSizeBytes,
            publishedAt = publishedAt
        )
    }

    @Test
    fun `valid dto passes validation and builds correct same-origin url`() {
        val dto = createValidDto()
        val result = UpdateMetadataValidator.validate(dto, validBaseUrl, allowInsecure = true)

        assertTrue(result is MetadataValidationResult.Success)
        val meta = (result as MetadataValidationResult.Success).metadata
        assertEquals(1, meta.schemaVersion)
        assertEquals("com.nexustvguide.app", meta.applicationId)
        assertEquals(2L, meta.versionCode)
        assertEquals("0.6.0", meta.versionName)
        assertEquals("http://192.168.2.171:3000/api/v1/app/download/nexus-tv-guide-0.6.0.apk", meta.downloadUrl)
    }

    @Test
    fun `rejects unsupported schema version`() {
        val dto = createValidDto(schemaVersion = 2)
        val result = UpdateMetadataValidator.validate(dto, validBaseUrl, allowInsecure = true)
        assertTrue(result is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects foreign applicationId`() {
        val dto = createValidDto(applicationId = "com.malicious.app")
        val result = UpdateMetadataValidator.validate(dto, validBaseUrl, allowInsecure = true)
        assertTrue(result is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects non-positive version code`() {
        val dtoZero = createValidDto(versionCode = 0)
        assertTrue(UpdateMetadataValidator.validate(dtoZero, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)

        val dtoNeg = createValidDto(versionCode = -5)
        assertTrue(UpdateMetadataValidator.validate(dtoNeg, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid version name`() {
        val dtoBlank = createValidDto(versionName = "   ")
        assertTrue(UpdateMetadataValidator.validate(dtoBlank, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)

        val dtoLong = createValidDto(versionName = "a".repeat(65))
        assertTrue(UpdateMetadataValidator.validate(dtoLong, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid download path`() {
        // Path traversal
        val dtoTraversal = createValidDto(downloadPath = "/api/v1/app/download/../other.apk")
        assertTrue(UpdateMetadataValidator.validate(dtoTraversal, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)

        // Non-apk extension
        val dtoExe = createValidDto(downloadPath = "/api/v1/app/download/nexus.exe")
        assertTrue(UpdateMetadataValidator.validate(dtoExe, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)

        // Query params
        val dtoQuery = createValidDto(downloadPath = "/api/v1/app/download/nexus.apk?token=123")
        assertTrue(UpdateMetadataValidator.validate(dtoQuery, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid sha256`() {
        val dtoShort = createValidDto(sha256 = "9a4f2f")
        assertTrue(UpdateMetadataValidator.validate(dtoShort, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)

        val dtoInvalidChar = createValidDto(sha256 = "z".repeat(64))
        assertTrue(UpdateMetadataValidator.validate(dtoInvalidChar, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid file size`() {
        val dtoZero = createValidDto(fileSizeBytes = 0)
        assertTrue(UpdateMetadataValidator.validate(dtoZero, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)

        val dtoOversize = createValidDto(fileSizeBytes = 101 * 1024 * 1024L)
        assertTrue(UpdateMetadataValidator.validate(dtoOversize, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid publishedAt instant`() {
        val dtoInvalidDate = createValidDto(publishedAt = "invalid-date")
        assertTrue(UpdateMetadataValidator.validate(dtoInvalidDate, validBaseUrl, allowInsecure = true) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid base url`() {
        val dto = createValidDto()
        val result = UpdateMetadataValidator.validate(dto, "not-a-valid-url", allowInsecure = true)
        assertTrue(result is MetadataValidationResult.Invalid)
    }

    // --- Release-kanaal (absolute downloadUrl + host-allowlist) ---

    private val releaseBaseUrl = "https://github.com/djawiz/NexusTVGuide/releases/latest/download/"
    private val releaseAllowlist = setOf("github.com", "objects.githubusercontent.com")

    private fun releaseDto(
        downloadUrl: String? = "https://objects.githubusercontent.com/gh/nexus-tv-guide-0.7.0.apk",
        downloadPath: String? = null
    ) = createValidDto(versionCode = 7, versionName = "0.7.0", downloadPath = downloadPath, downloadUrl = downloadUrl)

    @Test
    fun `accepts absolute downloadUrl on allowlisted asset host`() {
        val result = UpdateMetadataValidator.validate(releaseDto(), releaseBaseUrl, releaseAllowlist)

        assertTrue("Verwacht Success, kreeg: $result", result is MetadataValidationResult.Success)
        val meta = (result as MetadataValidationResult.Success).metadata
        assertEquals("https://objects.githubusercontent.com/gh/nexus-tv-guide-0.7.0.apk", meta.downloadUrl)
        assertEquals(7L, meta.versionCode)
    }

    @Test
    fun `accepts absolute downloadUrl on the metadata origin itself`() {
        // Same-origin blijft toegestaan, ook zonder allowlist-vermelding.
        val dto = releaseDto(downloadUrl = releaseBaseUrl + "nexus-tv-guide-0.7.0.apk")
        val result = UpdateMetadataValidator.validate(dto, releaseBaseUrl, allowlist = emptySet())
        assertTrue(result is MetadataValidationResult.Success)
    }

    @Test
    fun `rejects downloadUrl on a host outside the allowlist`() {
        val dto = releaseDto(downloadUrl = "https://evil.example.com/nexus-tv-guide-0.7.0.apk")
        val result = UpdateMetadataValidator.validate(dto, releaseBaseUrl, releaseAllowlist)

        assertTrue(result is MetadataValidationResult.Invalid)
        assertTrue(
            (result as MetadataValidationResult.Invalid).reason.contains("evil.example.com")
        )
    }

    @Test
    fun `rejects plain http downloadUrl on a secure channel`() {
        // Zelfs een toegestane host mag niet over http leveren als het kanaal https is.
        val dto = releaseDto(downloadUrl = "http://github.com/nexus-tv-guide-0.7.0.apk")
        val result = UpdateMetadataValidator.validate(dto, releaseBaseUrl, releaseAllowlist, allowInsecure = false)
        assertTrue(result is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects downloadUrl that does not point at an apk`() {
        val dto = releaseDto(downloadUrl = "https://github.com/releases/payload.sh")
        val result = UpdateMetadataValidator.validate(dto, releaseBaseUrl, releaseAllowlist)
        assertTrue(result is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects manifest carrying both downloadUrl and downloadPath`() {
        val dto = releaseDto(
            downloadUrl = "https://github.com/nexus-tv-guide-0.7.0.apk",
            downloadPath = "/api/v1/app/download/nexus-tv-guide-0.7.0.apk"
        )
        val result = UpdateMetadataValidator.validate(dto, releaseBaseUrl, releaseAllowlist)
        assertTrue(result is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects manifest without any download reference`() {
        val dto = releaseDto(downloadUrl = null, downloadPath = null)
        val result = UpdateMetadataValidator.validate(dto, releaseBaseUrl, releaseAllowlist)
        assertTrue(result is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects malformed absolute downloadUrl`() {
        val dto = releaseDto(downloadUrl = "ftp://github.com/nexus.apk")
        val result = UpdateMetadataValidator.validate(dto, releaseBaseUrl, releaseAllowlist)
        assertTrue(result is MetadataValidationResult.Invalid)
    }
}
