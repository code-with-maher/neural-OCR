package com.a.labs.data.remote

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

class ElevenLabsClient(private val client: OkHttpClient) {

    private val voiceId = "GHszn56Ads7pHU1bODA2"
    private val baseUrl = "https://api.elevenlabs.io/v1/text-to-speech/$voiceId"

    suspend fun generateSpeech(
        text: String,
        apiKey: String,
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val json = JSONObject().apply {
                put("text", text)
                put("model_id", "eleven_v3")
                put("voice_settings", JSONObject().apply {
                    put("stability", 0.5)
                    put("similarity_boost", 0.8)
                })
            }

            val request = Request.Builder()
                .url(baseUrl)
                .addHeader("xi-api-key", apiKey)
                .addHeader("Content-Type", "application/json")
                .post(json.toString().toRequestBody("application/json".toMediaType()))
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "Unknown error"
                    return@withContext Result.failure(Exception("HTTP ${response.code}: $errorBody"))
                }

                response.body?.byteStream()?.use { input ->
                    FileOutputStream(outputFile).use { output ->
                        input.copyTo(output)
                    }
                }
                Result.success(outputFile)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
