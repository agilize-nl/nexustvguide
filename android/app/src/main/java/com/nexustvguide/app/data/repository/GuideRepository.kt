package com.nexustvguide.app.data.repository

import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.GuideResponseDto
import kotlinx.coroutines.flow.Flow

interface GuideRepository {
    suspend fun getChannels(): List<ChannelDto>
    suspend fun getChannelsForOrdering(date: String): List<ChannelDto>
    suspend fun getGuideForDate(date: String): GuideResponseDto?
    fun observeGuideForDate(date: String): Flow<GuideResponseDto?>
}
