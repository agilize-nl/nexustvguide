package com.nexustvguide.app.core.domain

data class Channel(
    val id: String,
    val sourceId: String,
    val name: String,
    val logoUrl: String? = null,
    val inNlziet: Boolean = false,
    val nlzietSlug: String? = null,
    val nlzietChannelId: String? = null,
    val sortOrder: Int = 999
)
