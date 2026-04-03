package com.a.labs.data.local

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.a.labs.core.GeminiModels
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsManager(private val context: Context) {

    companion object {
        val GEMINI_KEY = stringPreferencesKey("gemini_api_key")
        val ELEVEN_KEY = stringPreferencesKey("eleven_api_key")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val GEMINI_MODEL_KEY = stringPreferencesKey("gemini_model")
    }

    val geminiKey: Flow<String> = context.dataStore.data.map { it[GEMINI_KEY] ?: "" }
    val elevenKey: Flow<String> = context.dataStore.data.map { it[ELEVEN_KEY] ?: "" }
    val ttsEngine: Flow<String> = context.dataStore.data.map { it[TTS_ENGINE] ?: "SYSTEM" }
    val geminiModel: Flow<String> = context.dataStore.data.map { it[GEMINI_MODEL_KEY] ?: GeminiModels.FLASH_2_5 }

    suspend fun saveSetting(key: androidx.datastore.preferences.core.Preferences.Key<String>, value: String) {
        context.dataStore.edit { it[key] = value }
    }
}
