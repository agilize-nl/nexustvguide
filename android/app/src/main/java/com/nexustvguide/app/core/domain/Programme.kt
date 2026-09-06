package com.nexustvguide.app.core.domain

data class NlzietProgrammeTarget(
    val kind: String = "replay",
    val contentItemId: String,
    val assetId: String,
    val channelId: String,
    val isReplayAllowed: Boolean = false,
    val isRestartAllowed: Boolean = false
)

data class Programme(
    val id: String,
    val channelId: String,
    val title: String,
    val start: String,         // RFC 3339 UTC ISO
    val end: String,           // RFC 3339 UTC ISO
    val description: String? = null,
    val imageUrl: String? = null,
    val genre: String? = null,
    val isLive: Boolean = false,
    val isRerun: Boolean = false,
    val isPremiere: Boolean = false,
    val ageRating: String? = null,
    var nlziet: NlzietProgrammeTarget? = null,
    var nlzietId: String? = null
)
