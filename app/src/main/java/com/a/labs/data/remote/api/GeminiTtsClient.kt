package com.a.labs.data.remote.api

import android.content.Context
import com.a.labs.core.GeminiModels
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
private data class TtsRequest(
    val contents: List<TtsContent>,
    val generationConfig: TtsGenerationConfig
)

@Serializable
private data class TtsContent(val parts: List<TtsPart>)

@Serializable
private data class TtsPart(val text: String)

@Serializable
private data class TtsGenerationConfig(
    val responseModalities: List<String> = listOf("AUDIO"),
    val speechConfig: TtsSpeechConfig? = null
)

@Serializable
private data class TtsSpeechConfig(val voiceConfig: TtsVoiceConfig)

@Serializable
private data class TtsVoiceConfig(val prebuiltVoiceConfig: TtsPrebuiltVoiceConfig)

@Serializable
private data class TtsPrebuiltVoiceConfig(val voiceName: String)

@Serializable
private data class TtsResponse(val candidates: List<TtsCandidate>? = null)

@Serializable
private data class TtsCandidate(val content: TtsResponseContent? = null)

@Serializable
private data class TtsResponseContent(val parts: List<TtsResponsePart>? = null)

@Serializable
private data class TtsResponsePart(val inlineData: TtsInlineData? = null)

@Serializable
private data class TtsInlineData(
    val mimeType: String? = null,
    val data: String? = null 
)

class GeminiTtsClient(
    private val context: Context,
    private val client: OkHttpClient,
    private val apiKey: String
) {
    private val jsonConfig = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun generateSpeech(text: String, fileName: String, voiceName: String = "Aoede"): Result<File> = withContext(Dispatchers.IO) {
        try {
            // استخدام النموذج الصحيح من ملف الكتالوج
            val url = "https://generativelanguage.googleapis.com/v1beta/models/${GeminiModels.TTS_MODEL}:generateContent?key=$apiKey"
            val requestBodyDto = TtsRequest(
                contents = listOf(TtsContent(listOf(TtsPart(text)))),
                generationConfig = TtsGenerationConfig(
                    speechConfig = TtsSpeechConfig(TtsVoiceConfig(TtsPrebuiltVoiceConfig(voiceName)))
                )
            )
            val jsonBody = jsonConfig.encodeToString(requestBodyDto)
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toRequestBody("application/json".toMediaType()))
                .build()
            val response = client.newCall(request).execute()
            val responseString = response.body.string()
            
            if (response.isSuccessful) {
                val ttsResponse = jsonConfig.decodeFromString<TtsResponse>(responseString)
                val base64Audio = ttsResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.inlineData?.data
                if (base64Audio != null) {
                    val audioBytes = android.util.Base64.decode(base64Audio, android.util.Base64.DEFAULT)
                    val outputFile = File(context.cacheDir, "$fileName.wav")
                    saveAsWav(audioBytes, outputFile, 24000, 1)
                    Result.success(outputFile)
                } else Result.failure(Exception("No audio data"))
            } else Result.failure(Exception("API Error: ${response.code}"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun saveAsWav(pcmData: ByteArray, file: File, sampleRate: Int, channels: Int) {
        val totalDataLen = pcmData.size + 36
        val byteRate = sampleRate * channels * 2
        val header =  ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray())
             putInt(totalDataLen)
            put("WAVE".toByteArray())
            put("fmt ".toByteArray())
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort((channels * 2).toShort())
            putShort(16.toShort())
            put("data".toByteArray())
            putInt(pcmData.size)
        }.array()
        FileOutputStream(file).use { output ->
            output.write(header)
            output.write(pcmData)
        }
      }
}