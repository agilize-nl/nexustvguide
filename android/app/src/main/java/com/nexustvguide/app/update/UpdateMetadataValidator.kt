package com.nexustvguide.app.update

import com.nexustvguide.app.data.model.AppUpdateDto
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.threeten.bp.Instant

data class ValidatedUpdateMetadata(
    val schemaVersion: Int,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val releaseNotes: String?,
    val downloadUrl: String,
    val sha256: String,
    val fileSizeBytes: Long,
    val publishedAt: String
)

sealed class MetadataValidationResult {
    data class Success(val metadata: ValidatedUpdateMetadata) : MetadataValidationResult()
    data class Invalid(val reason: String) : MetadataValidationResult()
}

object UpdateMetadataValidator {

    private const val EXPECTED_APPLICATION_ID = "com.nexustvguide.app"
    private const val MAX_VERSION_NAME_LENGTH = 64
    private const val MAX_RELEASE_NOTES_LENGTH = 8000
    private const val MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024L // 100 MiB
    private val DOWNLOAD_PATH_REGEX = Regex("^/api/v1/app/download/[a-zA-Z0-9._-]+\\.apk$")
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    fun validate(dto: AppUpdateDto, updateBaseUrl: String): MetadataValidationResult {
        if (dto.schemaVersion != 1) {
            return MetadataValidationResult.Invalid("Niet-ondersteunde schemaversie: ${dto.schemaVersion}")
        }

        if (dto.applicationId != EXPECTED_APPLICATION_ID) {
            return MetadataValidationResult.Invalid("Ongeldig applicationId: '${dto.applicationId}', verwacht '$EXPECTED_APPLICATION_ID'")
        }

        if (dto.versionCode <= 0) {
            return MetadataValidationResult.Invalid("versionCode moet positief zijn, ontvangen: ${dto.versionCode}")
        }

        if (dto.versionName.isBlank() || dto.versionName.length > MAX_VERSION_NAME_LENGTH) {
            return MetadataValidationResult.Invalid("Ongeldige versionName lengte (${dto.versionName.length})")
        }

        if (dto.releaseNotes != null && dto.releaseNotes.length > MAX_RELEASE_NOTES_LENGTH) {
            return MetadataValidationResult.Invalid("Release notes overschrijden maximale lengte van $MAX_RELEASE_NOTES_LENGTH tekens")
        }

        if (!DOWNLOAD_PATH_REGEX.matches(dto.downloadPath)) {
            return MetadataValidationResult.Invalid("Ongeldig downloadPath: '${dto.downloadPath}'")
        }

        if (!SHA256_REGEX.matches(dto.sha256)) {
            return MetadataValidationResult.Invalid("Ongeldige SHA-256 hash formaat")
        }

        if (dto.fileSizeBytes <= 0 || dto.fileSizeBytes > MAX_FILE_SIZE_BYTES) {
            return MetadataValidationResult.Invalid("Bestandsgrootte buiten toegestane grenzen (1 .. 100 MiB): ${dto.fileSizeBytes} bytes")
        }

        try {
            Instant.parse(dto.publishedAt)
        } catch (e: Exception) {
            return MetadataValidationResult.Invalid("Ongeldige publishedAt ISO-instant: '${dto.publishedAt}'")
        }

        val baseHttpUrl = updateBaseUrl.toHttpUrlOrNull()
            ?: return MetadataValidationResult.Invalid("Ongeldige UPDATE_BASE_URL: '$updateBaseUrl'")

        val resolvedUrl = baseHttpUrl.resolve(dto.downloadPath)
            ?: return MetadataValidationResult.Invalid("Kan downloadUrl niet resolven met downloadPath: '${dto.downloadPath}'")

        // Same-origin verificatie
        if (resolvedUrl.scheme != baseHttpUrl.scheme ||
            resolvedUrl.host != baseHttpUrl.host ||
            resolvedUrl.port != baseHttpUrl.port
        ) {
            return MetadataValidationResult.Invalid("Download URL schendt same-origin beleid: '${resolvedUrl}' vs '${baseHttpUrl}'")
        }

        val validated = ValidatedUpdateMetadata(
            schemaVersion = dto.schemaVersion,
            applicationId = dto.applicationId,
            versionCode = dto.versionCode,
            versionName = dto.versionName,
            releaseNotes = dto.releaseNotes,
            downloadUrl = resolvedUrl.toString(),
            sha256 = dto.sha256.lowercase(),
            fileSizeBytes = dto.fileSizeBytes,
            publishedAt = dto.publishedAt
        )

        return MetadataValidationResult.Success(validated)
    }
}
