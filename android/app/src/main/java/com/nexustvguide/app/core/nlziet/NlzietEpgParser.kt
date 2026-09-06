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
        val root = requireObject(JsonParser.parseString(jsonString))
        val data = root.get("data")
        require(data != null && data.isJsonArray) { "Missing EPG data array" }
        return NlzietEpgResponse(data.asJsonArray.map { group ->
            val obj = requireObject(group)
            val channel = requireObject(requireObject(obj.get("channel")).get("content"))
            val channelId = requireString(channel, "id")
            require(channelId.isNotEmpty()) { "Empty channel ID" }
            val title = if (channel.has("title")) requireString(channel, "title") else null
            val locations = obj.get("programLocations")
            require(locations == null || locations.isJsonArray) { "Invalid programLocations" }
            NlzietEpgChannelGroup(channelId, title, locations?.asJsonArray?.map { location ->
                NlzietEpgProgramLocation(parseContent(requireObject(requireObject(location).get("content"))))
            } ?: emptyList())
        })
    }

    private fun parseContent(obj: JsonObject): NlzietEpgContent {
        val contentItemId = requireString(obj, "contentItemId")
        val assetId = requireString(obj, "assetId")
        val title = requireString(obj, "title")
        val startAt = requireString(obj, "startAt")
        val endAt = requireString(obj, "endAt")
        require(CONTENT_ITEM_ID_REGEX.matches(contentItemId)) { "Invalid contentItemId" }
        require(ASSET_ID_REGEX.matches(assetId)) { "Invalid assetId" }
        require(title.isNotEmpty()) { "Empty EPG title" }
        for (time in listOf(startAt, endAt)) {
            // Require seconds and an explicit offset, as in the source schema.
            require(Regex(".*T[0-9]{2}:[0-9]{2}:[0-9]{2}(\\.[0-9]+)?(Z|[+-][0-9]{2}:?[0-9]{2})$").matches(time))
            OffsetDateTime.parse(time)
        }
        val seriesId = if (!obj.has("seriesId") || obj.get("seriesId").isJsonNull) null else requireString(obj, "seriesId")
        return NlzietEpgContent(contentItemId, assetId, title, startAt, endAt,
            booleanOrDefault(obj, "isReplayAllowed"), booleanOrDefault(obj, "isRestartAllowed"), seriesId)
    }

    private fun requireObject(element: JsonElement?): JsonObject {
        require(element != null && element.isJsonObject) { "Expected EPG object" }
        return element.asJsonObject
    }

    private fun requireString(obj: JsonObject, key: String): String {
        val value = obj.get(key)
        require(value != null && value.isJsonPrimitive && value.asJsonPrimitive.isString) { "Invalid EPG $key" }
        return value.asString
    }

    private fun booleanOrDefault(obj: JsonObject, key: String): Boolean {
        val value = obj.get(key) ?: return false
        require(value.isJsonPrimitive && value.asJsonPrimitive.isBoolean) { "Invalid EPG $key" }
        return value.asBoolean
    }
}
