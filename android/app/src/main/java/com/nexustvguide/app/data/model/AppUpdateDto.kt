package com.nexustvguide.app.data.model

import com.google.gson.annotations.SerializedName

/**
 * Data Transfer Object voor externe update-metadata van GET /api/v1/app/version.
 */
data class AppUpdateDto(
    @SerializedName("schemaVersion") val schemaVersion: Int,
    @SerializedName("applicationId") val applicationId: String,
    @SerializedName("versionCode") val versionCode: Long,
    @SerializedName("versionName") val versionName: String,
    @SerializedName("releaseNotes") val releaseNotes: String?,
    @SerializedName("downloadPath") val downloadPath: String,
    @SerializedName("sha256") val sha256: String,
    @SerializedName("fileSizeBytes") val fileSizeBytes: Long,
    @SerializedName("publishedAt") val publishedAt: String
)
