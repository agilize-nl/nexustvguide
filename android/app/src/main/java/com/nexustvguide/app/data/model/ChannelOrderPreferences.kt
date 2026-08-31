package com.nexustvguide.app.data.model

data class ChannelOrderPreferences(
    val version: Int = CURRENT_VERSION,
    /**
     * Channel-id's in door de gebruiker gekozen volgorde. Mag id's bevatten die de
     * backend niet meer levert; die worden bij het toepassen genegeerd.
     */
    val orderedIds: List<String> = emptyList(),
    /**
     * Channel-id's die de gebruiker heeft verborgen.
     */
    val hiddenIds: Set<String> = emptySet()
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
