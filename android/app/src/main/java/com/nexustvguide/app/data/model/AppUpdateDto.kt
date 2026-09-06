package com.nexustvguide.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Data Transfer Object voor externe update-metadata.
 *
 * Twee kanalen leveren dit document:
 * - de LAN-backend via `GET /api/v1/app/version`, met een relatief [downloadPath];
 * - een release-host (GitHub/Forgejo) via een gepubliceerd `version.json`, met een
 *   absolute [downloadUrl] omdat de asset op een andere host staat dan de metadata.
 *
 * Precies één van beide velden moet gevuld zijn; [UpdateMetadataValidator] dwingt dat af.
 */
data class AppUpdateDto(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    @SerializedName("applicationId") val applicationId: String,
    @SerializedName("versionCode") val versionCode: Long,
    @SerializedName("versionName") val versionName: String,
    @SerializedName("releaseNotes") val releaseNotes: String?,
    /** Relatief pad op de eigen backend, bijv. `/api/v1/app/download/nexus-tv-guide-0.6.0.apk`. */
    @SerializedName("downloadPath") val downloadPath: String? = null,
    /** Absolute HTTPS-URL naar het APK-asset op een release-host. */
    @SerializedName("downloadUrl") val downloadUrl: String? = null,
    @SerializedName("sha256") val sha256: String,
    @SerializedName("fileSizeBytes") val fileSizeBytes: Long,
    @SerializedName("publishedAt") val publishedAt: String
)
