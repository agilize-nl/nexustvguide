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
        downloadPath: String = "/api/v1/app/download/nexus-tv-guide-0.6.0.apk",
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
            sha256 = sha256,
            fileSizeBytes = fileSizeBytes,
            publishedAt = publishedAt
        )
    }

    @Test
    fun `valid dto passes validation and builds correct same-origin url`() {
        val dto = createValidDto()
        val result = UpdateMetadataValidator.validate(dto, validBaseUrl)

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
        val result = UpdateMetadataValidator.validate(dto, validBaseUrl)
        assertTrue(result is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects foreign applicationId`() {
        val dto = createValidDto(applicationId = "com.malicious.app")
        val result = UpdateMetadataValidator.validate(dto, validBaseUrl)
        assertTrue(result is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects non-positive version code`() {
        val dtoZero = createValidDto(versionCode = 0)
        assertTrue(UpdateMetadataValidator.validate(dtoZero, validBaseUrl) is MetadataValidationResult.Invalid)

        val dtoNeg = createValidDto(versionCode = -5)
        assertTrue(UpdateMetadataValidator.validate(dtoNeg, validBaseUrl) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid version name`() {
        val dtoBlank = createValidDto(versionName = "   ")
        assertTrue(UpdateMetadataValidator.validate(dtoBlank, validBaseUrl) is MetadataValidationResult.Invalid)

        val dtoLong = createValidDto(versionName = "a".repeat(65))
        assertTrue(UpdateMetadataValidator.validate(dtoLong, validBaseUrl) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid download path`() {
        // Path traversal
        val dtoTraversal = createValidDto(downloadPath = "/api/v1/app/download/../other.apk")
        assertTrue(UpdateMetadataValidator.validate(dtoTraversal, validBaseUrl) is MetadataValidationResult.Invalid)

        // Non-apk extension
        val dtoExe = createValidDto(downloadPath = "/api/v1/app/download/nexus.exe")
        assertTrue(UpdateMetadataValidator.validate(dtoExe, validBaseUrl) is MetadataValidationResult.Invalid)

        // Query params
        val dtoQuery = createValidDto(downloadPath = "/api/v1/app/download/nexus.apk?token=123")
        assertTrue(UpdateMetadataValidator.validate(dtoQuery, validBaseUrl) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid sha256`() {
        val dtoShort = createValidDto(sha256 = "9a4f2f")
        assertTrue(UpdateMetadataValidator.validate(dtoShort, validBaseUrl) is MetadataValidationResult.Invalid)

        val dtoInvalidChar = createValidDto(sha256 = "z".repeat(64))
        assertTrue(UpdateMetadataValidator.validate(dtoInvalidChar, validBaseUrl) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid file size`() {
        val dtoZero = createValidDto(fileSizeBytes = 0)
        assertTrue(UpdateMetadataValidator.validate(dtoZero, validBaseUrl) is MetadataValidationResult.Invalid)

        val dtoOversize = createValidDto(fileSizeBytes = 101 * 1024 * 1024L)
        assertTrue(UpdateMetadataValidator.validate(dtoOversize, validBaseUrl) is MetadataValidationResult.Invalid)
    }

    @Test
    fun `rejects invalid publishedAt instant`() {
        val dtoInvalidDate = createValidDto(publishedAt = "invalid-date")
        assertTrue(UpdateMetadataValidator.validate(dtoInvalidDate, validBaseUrl) is MetadataValidationResult.Invalid)
    }
}
