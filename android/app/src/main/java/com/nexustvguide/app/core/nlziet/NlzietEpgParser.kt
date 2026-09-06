package com.nexustvguide.app.core.nlziet

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.threeten.bp.OffsetDateTime
import org.threeten.bp.format.DateTimeParseException

data class NlzietEpgContent(
    val contentItemId: String,
    val assetId: String,
    val title: String,
    val startAt: String,
    val endAt: String,
    val isReplayAllowed: Boolean = false,
    val isRestartAllowed: Boolean = false,
    val seriesId: String? = null
)

data class NlzietEpgProgramLocation(
    val content: NlzietEpgContent
)

data class NlzietEpgChannelGroup(
    val channelId: String,
    val channelTitle: String? = null,
    val programLocations: List<NlzietEpgProgramLocation> = emptyList()
)

data class NlzietEpgResponse(
    val data: List<NlzietEpgChannelGroup> = emptyList()
)

object NlzietEpgParser {
    private val CONTENT_ITEM_ID_REGEX = Regex("^[A-Za-z0-9_-]{22}$")
    private val ASSET_ID_REGEX = Regex("^[A-Fa-f0-9]{32}$")

    fun parseNlzietEpgResponse(jsonString: String): NlzietEpgResponse {
        val rootElement = JsonParser.parseString(jsonString)
        if (!rootElement.isJsonObject) {
            throw IllegalArgumentException("Root must be a JSON object")
        }
        val root = rootElement.asJsonObject
        val dataArray = root.getAsJsonArray("data") ?: throw IllegalArgumentException("Missing data array in NLZIET EPG")

        val channelGroups = mutableListOf<NlzietEpgChannelGroup>()

        for (groupElement in dataArray) {
            if (!groupElement.isJsonObject) continue
            val groupObj = groupElement.asJsonObject

            val channelObj = groupObj.getAsJsonObject("channel") ?: continue
            val channelContent = channelObj.getAsJsonObject("content") ?: continue
            val chId = channelContent.get("id")?.asString ?: continue
            if (chId.isEmpty()) continue

            val chTitle = channelContent.get("title")?.asString

            val locationsList = mutableListOf<NlzietEpgProgramLocation>()
            val progLocationsArray = groupObj.getAsJsonArray("programLocations")
            if (progLocationsArray != null) {
                for (locElement in progLocationsArray) {
                    if (!locElement.isJsonObject) continue
                    val contentObj = locElement.asJsonObject.getAsJsonObject("content") ?: continue
                    val content = parseContent(contentObj)
                    if (content != null) {
                        locationsList.add(NlzietEpgProgramLocation(content))
                    }
                }
            }

            channelGroups.add(
                NlzietEpgChannelGroup(
                    channelId = chId,
                    channelTitle = chTitle,
                    programLocations = locationsList
                )
            )
        }

        return NlzietEpgResponse(data = channelGroups)
    }

    private fun parseContent(obj: JsonObject): NlzietEpgContent? {
        val contentItemId = getString(obj, "contentItemId") ?: return null
        val assetId = getString(obj, "assetId") ?: return null
        val title = getString(obj, "title") ?: return null
        val startAt = getString(obj, "startAt") ?: return null
        val endAt = getString(obj, "endAt") ?: return null

        if (!CONTENT_ITEM_ID_REGEX.matches(contentItemId)) return null
        if (!ASSET_ID_REGEX.matches(assetId)) return null
        if (title.isEmpty()) return null

        try {
            OffsetDateTime.parse(startAt)
            OffsetDateTime.parse(endAt)
        } catch (e: DateTimeParseException) {
            return null
        }

        val isReplayAllowed = getBoolean(obj, "isReplayAllowed", false)
        val isRestartAllowed = getBoolean(obj, "isRestartAllowed", false)
        val seriesId = getString(obj, "seriesId")

        return NlzietEpgContent(
            contentItemId = contentItemId,
            assetId = assetId,
            title = title,
            startAt = startAt,
            endAt = endAt,
            isReplayAllowed = isReplayAllowed,
            isRestartAllowed = isRestartAllowed,
            seriesId = seriesId
        )
    }

    private fun getString(obj: JsonObject, key: String): String? {
        val el: JsonElement? = obj.get(key)
        if (el == null || el.isJsonNull) return null
        if (el.isJsonPrimitive && el.asJsonPrimitive.isString) {
            return el.asString
        }
        return null
    }

    private fun getBoolean(obj: JsonObject, key: String, default: Boolean): Boolean {
        val el: JsonElement? = obj.get(key)
        if (el == null || el.isJsonNull) return default
        if (el.isJsonPrimitive && el.asJsonPrimitive.isBoolean) {
            return el.asBoolean
        }
        return default
    }
}
