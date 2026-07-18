package com.a.labs.data.remote.api

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

@Serializable
data class ElevenLabsRequest(
    val text: String,
    val model_id: String = "eleven_multilingual_v2",
    val voice_settings: VoiceSettings = VoiceSettings()
)

@Serializable
data class VoiceSettings(
    val stability: Float = 0.5f,
    val similarity_boost: Float = 0.75f
)

class ElevenLabsClient(
    private val context: Context,
    private val client: OkHttpClient,
    private val apiKey: String
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun generateSpeech(text: String, fileName: String, voiceId: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val url = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"
            val requestBody = json.encodeToString(ElevenLabsRequest(text))
            val request = Request.Builder()
                .url(url)
                .header("xi-api-key", apiKey)
                .post(requestBody.toRequestBody("application/json".toMediaType()))
                .build()

            val response = client.newCall(request).execute()

            if (response.isSuccessful) {
                val outputFile = File(context.cacheDir, "$fileName.mp3")
                response.body.byteStream().use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Result.success(outputFile)
            } else {
                val errorBody = response.body.string()
                Result.failure(Exception("ElevenLabs Error: ${response.code} - $errorBody"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
