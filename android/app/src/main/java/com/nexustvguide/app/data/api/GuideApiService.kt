package com.nexustvguide.app.data.api

import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.GuideResponseDto
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

interface GuideApiService {

    @GET("api/v1/channels")
    suspend fun getChannels(): List<ChannelDto>

    @GET("api/v1/guide")
    suspend fun getGuideForDate(
        @Query("date") date: String,
        @Header("If-None-Match") ifNoneMatch: String? = null
    ): Response<GuideResponseDto>

    @GET("api/v1/guide")
    suspend fun getGuideForRange(
        @Query("from") from: String,
        @Query("to") to: String,
        @Header("If-None-Match") ifNoneMatch: String? = null
    ): Response<GuideResponseDto>
}
