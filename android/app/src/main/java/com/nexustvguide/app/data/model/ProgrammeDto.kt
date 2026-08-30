package com.nexustvguide.app.data.model

import android.os.Parcelable
import com.google.gson.annotations.SerializedName
import kotlinx.parcelize.Parcelize

@Parcelize
data class ProgrammeDto(
    @SerializedName("id") val id: String,
    @SerializedName("channelId") val channelId: String,
    @SerializedName("title") val title: String,
    @SerializedName("start") val start: String,         // RFC 3339 UTC ISO
    @SerializedName("end") val end: String,             // RFC 3339 UTC ISO
    @SerializedName("description") val description: String?,
    @SerializedName("imageUrl") val imageUrl: String?,
    @SerializedName("genre") val genre: String?,
    @SerializedName("isLive") val isLive: Boolean,
    @SerializedName("isRerun") val isRerun: Boolean,
    @SerializedName("isPremiere") val isPremiere: Boolean,
    @SerializedName("ageRating") val ageRating: String?,
    @SerializedName("nlzietId") val nlzietId: String? = null
) : Parcelable
