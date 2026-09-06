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
    private val LEGACY_DOWNLOAD_PATH_REGEX = Regex("^/api/v1/app/download/[a-zA-Z0-9._-]+\\.apk$")
    private val SHA256_REGEX = Regex("^[0-9a-fA-F]{64}$")

    /**
     * @param allowlist hosts die naast de eigen origin een APK mogen leveren; zie [UpdateOriginPolicy].
     * @param allowInsecure staat http toe (LAN-backend/emulator).
     */
    fun validate(
        dto: AppUpdateDto,
        updateBaseUrl: String,
        allowlist: Set<String> = emptySet(),
        allowInsecure: Boolean = false
    ): MetadataValidationResult {
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

        val resolvedUrl = when (val resolution = resolveDownloadUrl(dto, baseHttpUrl)) {
            is DownloadUrlResolution.Invalid -> return MetadataValidationResult.Invalid(resolution.reason)
            is DownloadUrlResolution.Resolved -> resolution.url
        }

        if (!resolvedUrl.encodedPath.endsWith(".apk", ignoreCase = true)) {
            return MetadataValidationResult.Invalid("Download-URL verwijst niet naar een .apk: '$resolvedUrl'")
        }

        // Hostbeleid vervangt de oude same-origin-eis: release-hosts leveren het asset vanaf
        // een andere host dan de metadata. De eigen origin blijft altijd toegestaan.
        val originCheck = UpdateOriginPolicy.check(
            url = resolvedUrl,
            allowlist = allowlist,
            allowInsecure = allowInsecure,
            sameOriginWith = baseHttpUrl
        )
        if (originCheck is UpdateOriginPolicy.Result.Rejected) {
            return MetadataValidationResult.Invalid(originCheck.reason)
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

    private sealed class DownloadUrlResolution {
        data class Resolved(val url: HttpUrl) : DownloadUrlResolution()
        data class Invalid(val reason: String) : DownloadUrlResolution()
    }

    /**
     * Kiest tussen het absolute [AppUpdateDto.downloadUrl] van een release-host en het
     * relatieve [AppUpdateDto.downloadPath] van de eigen backend. Beide tegelijk is
     * dubbelzinnig en wordt geweigerd, zodat een manifest nooit twee bronnen kan aanwijzen.
     */
    private fun resolveDownloadUrl(dto: AppUpdateDto, baseHttpUrl: HttpUrl): DownloadUrlResolution {
        val hasUrl = !dto.downloadUrl.isNullOrBlank()
        val hasPath = !dto.downloadPath.isNullOrBlank()

        if (hasUrl && hasPath) {
            return DownloadUrlResolution.Invalid("Manifest bevat zowel downloadUrl als downloadPath; precies één is vereist")
        }
        if (!hasUrl && !hasPath) {
            return DownloadUrlResolution.Invalid("Manifest bevat geen downloadUrl of downloadPath")
        }

        if (hasUrl) {
            val absolute = dto.downloadUrl!!.toHttpUrlOrNull()
                ?: return DownloadUrlResolution.Invalid("Ongeldige downloadUrl: '${dto.downloadUrl}'")
            return DownloadUrlResolution.Resolved(absolute)
        }

        // Legacy-pad van de LAN-backend blijft strikt: alleen het bekende downloadpad,
        // zonder traversal of querystring.
        val path = dto.downloadPath!!
        if (!LEGACY_DOWNLOAD_PATH_REGEX.matches(path)) {
            return DownloadUrlResolution.Invalid("Ongeldig downloadPath: '$path'")
        }
        val resolved = baseHttpUrl.resolve(path)
            ?: return DownloadUrlResolution.Invalid("Kan downloadUrl niet resolven met downloadPath: '$path'")
        return DownloadUrlResolution.Resolved(resolved)
    }
}
