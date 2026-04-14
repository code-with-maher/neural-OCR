package com.a.labs.data.local

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
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
        val ELEVEN_VOICE_ID = stringPreferencesKey("eleven_voice_id")
        val TTS_ENGINE = stringPreferencesKey("tts_engine")
        val GEMINI_MODEL_KEY = stringPreferencesKey("gemini_model")
        val CHUNK_SIZE_KEY = intPreferencesKey("chunk_size")
        val DEV_MODE_UNLOCKED = booleanPreferencesKey("dev_mode_unlocked")
        val LOGGING_ENABLED = booleanPreferencesKey("logging_enabled")
    }

    val geminiKey: Flow<String> = context.dataStore.data.map { it[GEMINI_KEY] ?: "" }
    val elevenKey: Flow<String> = context.dataStore.data.map { it[ELEVEN_KEY] ?: "" }
    val elevenVoiceId: Flow<String> = context.dataStore.data.map { it[ELEVEN_VOICE_ID] ?: "GHszn56Ads7pHU1bODA2" }
    val ttsEngine: Flow<String> = context.dataStore.data.map { it[TTS_ENGINE] ?: "SYSTEM" }
    val geminiModel: Flow<String> = context.dataStore.data.map { it[GEMINI_MODEL_KEY] ?: GeminiModels.FLASH_3_1_LITE }
    val chunkSize: Flow<Int> = context.dataStore.data.map { it[CHUNK_SIZE_KEY] ?: 15 }
    val isDevModeUnlocked: Flow<Boolean> = context.dataStore.data.map { it[DEV_MODE_UNLOCKED] ?: false }
    val isLoggingEnabled: Flow<Boolean> = context.dataStore.data.map { it[LOGGING_ENABLED] ?: false }

    suspend fun <T> saveSetting(key: androidx.datastore.preferences.core.Preferences.Key<T>, value: T) {
        context.dataStore.edit { it[key] = value }
    }
}