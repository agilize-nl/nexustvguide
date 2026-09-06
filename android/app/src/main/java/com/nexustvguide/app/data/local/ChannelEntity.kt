package com.nexustvguide.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "channels")
data class ChannelEntity(
    @PrimaryKey val id: String,
    val sourceId: String,
    val name: String,
    val logoUrl: String?,
    val inNlziet: Boolean,
    val nlzietSlug: String?,
    val nlzietChannelId: String?,
    val sortOrder: Int
)
