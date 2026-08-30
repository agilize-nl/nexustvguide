package com.nexustvguide.app.data.model

import com.google.gson.annotations.SerializedName

data class GuideResponseDto(
    @SerializedName("meta") val meta: GuideMetaDto,
    @SerializedName("channels") val channels: List<ChannelDto>,
    @SerializedName("programmes") val programmes: List<ProgrammeDto>
)
