package com.nexustvguide.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import com.nexustvguide.app.data.model.ChannelOrderPreferences
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

class ChannelOrderRepository(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val TAG = "ChannelOrderRepository"
        private const val PREFS_NAME = "channel_order"
        private const val KEY_PREFS_JSON = "prefs_json"
    }

    fun load(): ChannelOrderPreferences {
        val json = prefs.getString(KEY_PREFS_JSON, null) ?: return ChannelOrderPreferences()
        return try {
            val dto = gson.fromJson(json, ChannelOrderPreferences::class.java)
            if (dto == null || dto.version != ChannelOrderPreferences.CURRENT_VERSION) {
                Log.w(TAG, "Ongeldige of onbekende versie in channel order voorkeuren, defaults gebruikt")
                ChannelOrderPreferences()
            } else {
                ChannelOrderPreferences(
                    version = dto.version,
                    orderedIds = dto.orderedIds?.filterNotNull() ?: emptyList(),
                    hiddenIds = dto.hiddenIds?.filterNotNull()?.toSet() ?: emptySet()
                )
            }
        } catch (e: JsonParseException) {
            Log.w(TAG, "Fout bij parsen van channel order voorkeuren JSON, defaults gebruikt", e)
            ChannelOrderPreferences()
        } catch (e: Exception) {
            Log.w(TAG, "Onverwachte fout bij laden van channel order voorkeuren, defaults gebruikt", e)
            ChannelOrderPreferences()
        }
    }

    fun save(value: ChannelOrderPreferences) {
        try {
            val json = gson.toJson(value)
            prefs.edit().putString(KEY_PREFS_JSON, json).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Fout bij opslaan van channel order voorkeuren", e)
        }
    }

    fun reset() {
        prefs.edit().clear().apply()
    }

    fun observe(): Flow<ChannelOrderPreferences> = callbackFlow {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (key == KEY_PREFS_JSON || key == null) {
                trySend(load())
            }
        }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        trySend(load())

        awaitClose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }.distinctUntilChanged()
}
