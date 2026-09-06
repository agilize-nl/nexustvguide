package com.nexustvguide.app.core.domain

data class DaySnapshot(
    val date: String,
    val timeZone: String,
    val from: String,
    val to: String,
    val sourceFetchedAt: String,
    val publishedAt: String,
    val channels: List<Channel>,
    val programmes: List<Programme>
)
