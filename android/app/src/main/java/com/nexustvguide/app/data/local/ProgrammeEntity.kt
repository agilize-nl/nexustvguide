package com.nexustvguide.app.data.local

import androidx.room.Entity
import androidx.room.Index

@Entity(
    tableName = "programmes",
    primaryKeys = ["date", "channelId", "id"],
    indices = [Index(value = ["date", "channelId", "startUtcMs"])]
)
data class ProgrammeEntity(
    val date: String,             // Dagsnapshot in Europe/Amsterdam ("YYYY-MM-DD")
    val id: String,
    val channelId: String,
    val title: String,
    val startUtcMs: Long,          // Epoch-millis
    val endUtcMs: Long,
    val description: String?,
    val imageUrl: String?,
    val genre: String?,
    val isLive: Boolean,
    val isRerun: Boolean,
    val isPremiere: Boolean,
    val ageRating: String?,
    // Afgevlakt NlzietProgrammeTarget
    val nlzietKind: String?,
    val nlzietContentItemId: String?,
    val nlzietAssetId: String?,
    val nlzietChannelId: String?,
    val nlzietReplayAllowed: Boolean,
    val nlzietRestartAllowed: Boolean
)
