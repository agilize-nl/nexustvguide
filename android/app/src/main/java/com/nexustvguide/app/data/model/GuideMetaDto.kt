package com.nexustvguide.app.data.model

import com.google.gson.annotations.SerializedName

data class GuideMetaDto(
    @SerializedName("timeZone") val timeZone: String,
    @SerializedName("date") val date: String?,
    @SerializedName("from") val from: String,
    @SerializedName("to") val to: String,
    @SerializedName("lastSuccessfulRefresh") val lastSuccessfulRefresh: String,
    @SerializedName("stale") val stale: Boolean
)
