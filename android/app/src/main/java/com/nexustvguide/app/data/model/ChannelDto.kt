package com.nexustvguide.app.data.model

import com.google.gson.annotations.SerializedName

data class ChannelDto(
    @SerializedName("id") val id: String,
    @SerializedName("sourceId") val sourceId: String,
    @SerializedName("name") val name: String,
    @SerializedName("logoUrl") val logoUrl: String?,
    @SerializedName("inNlziet") val inNlziet: Boolean,
    @SerializedName("nlzietSlug") val nlzietSlug: String?,
    @SerializedName("sortOrder") val sortOrder: Int
)
