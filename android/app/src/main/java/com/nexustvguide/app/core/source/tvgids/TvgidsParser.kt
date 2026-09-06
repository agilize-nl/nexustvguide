package com.nexustvguide.app.core.source.tvgids

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

data class RawProgramme(
    val s: String,
    val e: String,
    val db_id: String,
    val title: String? = null,
    val descr: String? = null,
    val inhoud: String? = null,
    val algemene_inhoud: String? = null,
    val img: String? = null,
    val g_id: String? = null,
    val subgenre: String? = null,
    val tip: String? = null,
    val rerun: String? = null,
    val live: String? = null,
    val is_premiere: String? = null,
    val ei: String? = null,
    val is_type: String? = null
)

data class ValidationStats(
    var skippedMalformedProgrammesCount: Int = 0
)

object TvgidsParser {
    private val DIGIT_REGEX = Regex("^\\d+$")

    /**
     * Valideert de envelope van /v4/programs/ en retourneert een Map van ch_id naar geldige RawProgramme[].
     * Ondersteunt data als JSON object én als JSON array.
     */
    fun parseProgramsEnvelope(
        jsonString: String,
        stats: ValidationStats? = null
    ): Map<String, List<RawProgramme>> {
        val rootElement = JsonParser.parseString(jsonString)
        if (!rootElement.isJsonObject) {
            throw IllegalArgumentException("Root element must be a JSON object")
        }
        val root = rootElement.asJsonObject

        if (!root.has("version") || !root.get("version").isJsonPrimitive || !root.get("version").asJsonPrimitive.isString) {
            throw IllegalArgumentException("Invalid programs envelope: missing or non-string version")
        }

        if (!root.has("data")) {
            throw IllegalArgumentException("Invalid programs envelope: missing data")
        }

        val dataElement = root.get("data")
        val buckets = mutableListOf<JsonObject>()

        if (dataElement.isJsonObject) {
            for ((_, value) in dataElement.asJsonObject.entrySet()) {
                if (value.isJsonObject) {
                    buckets.add(value.asJsonObject)
                }
            }
        } else if (dataElement.isJsonArray) {
            for (item in dataElement.asJsonArray) {
                if (item.isJsonObject) {
                    buckets.add(item.asJsonObject)
                }
            }
        } else {
            throw IllegalArgumentException("Invalid programs envelope: data must be an object or array")
        }

        val result = mutableMapOf<String, List<RawProgramme>>()

        for (bucket in buckets) {
            val chIdElement = bucket.get("ch_id")
            if (chIdElement == null || !chIdElement.isJsonPrimitive || !chIdElement.asJsonPrimitive.isString) {
                continue
            }
            val chId = chIdElement.asString
            val progElement = bucket.get("prog")
            if (progElement == null || !progElement.isJsonArray) {
                result[chId] = emptyList()
                continue
            }

            val validProgrammes = mutableListOf<RawProgramme>()
            for (item in progElement.asJsonArray) {
                if (!item.isJsonObject) {
                    stats?.let { it.skippedMalformedProgrammesCount++ }
                    continue
                }
                val progObj = item.asJsonObject
                val parsedProg = parseAndValidateProgramme(progObj)
                if (parsedProg != null) {
                    validProgrammes.add(parsedProg)
                } else {
                    stats?.let { it.skippedMalformedProgrammesCount++ }
                }
            }
            result[chId] = validProgrammes
        }

        return result
    }

    private fun parseAndValidateProgramme(obj: JsonObject): RawProgramme? {
        val sStr = getStringProperty(obj, "s") ?: return null
        val eStr = getStringProperty(obj, "e") ?: return null
        val dbIdStr = getStringProperty(obj, "db_id") ?: return null

        if (!DIGIT_REGEX.matches(sStr) || !DIGIT_REGEX.matches(eStr) || !DIGIT_REGEX.matches(dbIdStr)) {
            return null
        }

        val sLong = sStr.toLongOrNull() ?: return null
        val eLong = eStr.toLongOrNull() ?: return null
        val dbIdLong = dbIdStr.toLongOrNull() ?: return null

        if (sLong <= 0 || eLong <= sLong || dbIdLong <= 0) {
            return null
        }

        val title = getStringProperty(obj, "title") ?: "(Geen titel)"
        val descr = getStringProperty(obj, "descr")
        val inhoud = getStringProperty(obj, "inhoud")
        val algemeneInhoud = getStringProperty(obj, "algemene_inhoud")
        val img = getStringProperty(obj, "img")
        val gId = getStringProperty(obj, "g_id")
        val subgenre = getStringProperty(obj, "subgenre")
        val tip = getStringProperty(obj, "tip")
        val rerun = getStringProperty(obj, "rerun")
        val live = getStringProperty(obj, "live")
        val isPremiere = getStringProperty(obj, "is_premiere")
        val ei = getStringProperty(obj, "ei")
        val isType = getStringProperty(obj, "is_type")

        return RawProgramme(
            s = sStr,
            e = eStr,
            db_id = dbIdStr,
            title = title,
            descr = descr,
            inhoud = inhoud,
            algemene_inhoud = algemeneInhoud,
            img = img,
            g_id = gId,
            subgenre = subgenre,
            tip = tip,
            rerun = rerun,
            live = live,
            is_premiere = isPremiere,
            ei = ei,
            is_type = isType
        )
    }

    private fun getStringProperty(obj: JsonObject, memberName: String): String? {
        val element: JsonElement? = obj.get(memberName)
        if (element == null || element.isJsonNull) return null
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            return element.asString
        }
        return null
    }
}
