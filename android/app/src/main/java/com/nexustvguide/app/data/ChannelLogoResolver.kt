package com.nexustvguide.app.data

import android.content.Context

/**
 * Zenderlogo's zitten als drawable in de APK (res/drawable-xxxhdpi/channel_logo_<id>.png).
 * Zo blijven ze zichtbaar zonder netwerk en zijn we niet afhankelijk van externe hosts:
 * imgur en wikimedia leveren bij een reeks verzoeken regelmatig HTTP 429 of een
 * foutpagina in plaats van een afbeelding.
 *
 * Kent een kanaal geen ingebakken logo (bijvoorbeeld een nieuwe zender die na de laatste
 * release aan channels.json is toegevoegd), dan valt [resolve] terug op de logoUrl uit
 * de configuratie.
 */
object ChannelLogoResolver {

    private const val DRAWABLE_PREFIX = "channel_logo_"

    // Cache: het aantal kanalen is klein en de lookup draait per rij van de gids.
    private val resIdCache = HashMap<String, Int>()

    /**
     * Geeft de bron waarmee Glide het logo van [channelId] moet laden: een
     * android.resource://-URI voor een ingebakken logo, anders [fallbackUrl].
     */
    fun resolve(context: Context, channelId: String, fallbackUrl: String?): String? {
        val resId = drawableIdFor(context, channelId)
        return if (resId != 0) {
            "android.resource://${context.packageName}/$resId"
        } else {
            fallbackUrl
        }
    }

    private fun drawableIdFor(context: Context, channelId: String): Int {
        resIdCache[channelId]?.let { return it }
        val name = DRAWABLE_PREFIX + channelId.lowercase()
        // getIdentifier is hier bewust: de logonamen volgen uit de data, niet uit R.
        @Suppress("DiscouragedApi")
        val resId = context.resources.getIdentifier(name, "drawable", context.packageName)
        resIdCache[channelId] = resId
        return resId
    }
}
