package com.nexustvguide.app.data

import com.nexustvguide.app.data.model.ChannelDto
import com.nexustvguide.app.data.model.ChannelOrderPreferences

object ChannelOrderResolver {

    fun apply(
        backendChannels: List<ChannelDto>,
        prefs: ChannelOrderPreferences
    ): List<ChannelDto> {
        if (backendChannels.isEmpty()) {
            return emptyList()
        }

        if (prefs.orderedIds.isEmpty() && prefs.hiddenIds.isEmpty()) {
            return backendChannels
        }

        val backendById = backendChannels.associateBy { it.id }

        // Stap 1: Neem orderedIds in volgorde; behoud alleen id's die in backendChannels voorkomen,
        // en sla een id over dat al eerder in de lijst stond (deduplicatie op eerste voorkomen).
        val result = mutableListOf<ChannelDto>()
        val seenOrdered = mutableSetOf<String>()
        for (id in prefs.orderedIds) {
            val channel = backendById[id]
            if (channel != null && seenOrdered.add(id)) {
                result.add(channel)
            }
        }

        // Stap 2 & 3: Bepaal de nieuwe zenders (in backendChannels maar niet in prefs.orderedIds).
        // Verwerk die in oplopende backendvolgorde en voeg elke nieuwe zender in ten opzichte van
        // zijn backend-voorgangers.
        val orderedIdSet = prefs.orderedIds.toSet()
        val newChannels = backendChannels.filter { it.id !in orderedIdSet }

        for (newChannel in newChannels) {
            val backendIndex = backendChannels.indexOfFirst { it.id == newChannel.id }
            var insertIndex = -1

            // Zoek vanaf de positie vóór de nieuwe zender terug naar de eerste voorganger
            // die al in de resultaatlijst staat.
            for (i in backendIndex - 1 downTo 0) {
                val predecessorId = backendChannels[i].id
                val posInResult = result.indexOfFirst { it.id == predecessorId }
                if (posInResult != -1) {
                    insertIndex = posInResult + 1
                    break
                }
            }

            if (insertIndex != -1) {
                result.add(insertIndex, newChannel)
            } else {
                result.add(0, newChannel)
            }
        }

        // Stap 4: Filter prefs.hiddenIds eruit.
        if (prefs.hiddenIds.isNotEmpty()) {
            return result.filter { it.id !in prefs.hiddenIds }
        }

        return result
    }
}
