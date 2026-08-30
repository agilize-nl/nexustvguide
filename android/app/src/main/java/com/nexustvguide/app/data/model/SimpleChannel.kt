package com.nexustvguide.app.data.model

import android.text.Spanned
import com.egeniq.androidtvprogramguide.entity.ProgramGuideChannel

data class SimpleChannel(
    override val id: String,
    override val name: Spanned,
    override val imageUrl: String? = null
) : ProgramGuideChannel
