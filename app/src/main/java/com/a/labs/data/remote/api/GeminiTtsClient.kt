package com.a.labs.data.remote.api

import android.content.Context
import android.util.Base64
import com.a.labs.core.GeminiModels
import com.a.labs.data.audio.GeminiAudioPlayer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.atomic.AtomicReference

@Serializable
private data class InteractionRequest(
    val model: String,
    val input: String,
    @SerialName("response_modalities")
    val responseModalities: List<String> = listOf("audio"),
    @SerialName("generation_config")
    val generationConfig: GenerationConfig? = null,
    val stream: Boolean = true
)

@Serializable
private data class GenerationConfig(
    @SerialName("speech_config")
    val speechConfig: SpeechConfig? = null
)

@Serializable
private data class SpeechConfig(
    val voice: String? = null,
    val language: String? = null
)

@Serializable
private data class SseEvent(
    @SerialName("event_type")
    val eventType: String? = null,
    val index: Int? = null,
    val delta: AudioDelta? = null,
    val error: ApiErrorDetail? = null
)

@Serializable
private data class AudioDelta(
    val type: String? = null,
    val data: String? = null,
    @SerialName("mime_type")
    val mimeType: String? = null,
    @SerialName("sample_rate")
    val sampleRate: Int? = null,
    val channels: Int? = null
)

@Serializable
private data class ApiErrorDetail(
    val code: Int? = null,
    val message: String? = null,
    val status: String? = null
)

class GeminiTtsClient(
    private val context: Context,
    private val client: OkHttpClient,
    private val apiKey: String
) {
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val activeCall = AtomicReference<Call?>(null)
    private var audioPlayer: GeminiAudioPlayer? = null

    companion object {
        private const val ENDPOINT =
            "https://generativelanguage.googleapis.com/v1beta/interactions?alt=sse"
        private const val DEFAULT_MODEL =
            "gemini-3.1-flash-tts-preview"
        private const val DEFAULT_SAMPLE_RATE = 24_000
        private const val DEFAULT_CHANNELS = 1
        private const val DEFAULT_BITS_PER_SAMPLE = 16
    }

    suspend fun generateSpeech(
        text: String,
        fileName: String,
        voiceName: String = "Aoede"
    ): Result<File> = withContext(Dispatchers.IO) {
        var player: GeminiAudioPlayer? = null
        var generationCompleted = false

        try {
            val model = GeminiModels.TTS_MODEL.ifBlank { DEFAULT_MODEL }

            val requestBody = InteractionRequest(
                model = model,
                input = text,
                responseModalities = listOf("audio"),
                generationConfig = GenerationConfig(
                    speechConfig = SpeechConfig(voice = voiceName)
                ),
                stream = true
            )

            val requestJson = jsonConfig.encodeToString(requestBody)

            val request = Request.Builder()
                .url(ENDPOINT)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(requestJson.toRequestBody("application/json".toMediaType()))
                .build()

            val call = client.newCall(request)
            activeCall.set(call)

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    val error = response.body.string()
                    return@withContext Result.failure(
                        Exception("Gemini Interactions API error: ${response.code} - $error")
                    )
                }

                val body = response.body

                BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8)).use { reader ->
                    var currentEventType: String? = null
                    var receivedAudio = false

                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val line = reader.readLine() ?: break

                        when {
                            line.startsWith("event:") -> {
                                currentEventType = line.substringAfter("event:").trim()
                            }

                            line.startsWith("data:") -> {
                                val data = line.substringAfter("data:").trim()
                                if (data.isEmpty() || data == "[DONE]") {
                                    continue
                                }

                                val event = try {
                                    jsonConfig.decodeFromString<SseEvent>(data)
                                } catch (_: Exception) {
                                    continue
                                }

                                if (event.error != null) {
                                    return@withContext Result.failure(
                                        Exception("Gemini stream error: ${event.error.message}")
                                    )
                                }

                                val isStepDelta = currentEventType == "step.delta" || event.eventType == "step.delta"
                                if (!isStepDelta) {
                                    continue
                                }

                                val delta = event.delta ?: continue
                                if (delta.type != "audio") {
                                    continue
                                }

                                val encodedAudio = delta.data ?: continue
                                if (encodedAudio.isEmpty()) {
                                    continue
                                }

                                val pcm = try {
                                    Base64.decode(encodedAudio, Base64.DEFAULT)
                                } catch (e: Exception) {
                                    throw Exception("Invalid Base64 audio data", e)
                                }

                                if (pcm.isEmpty()) {
                                    continue
                                }

                                if (!receivedAudio) {
                                    val sampleRate = delta.sampleRate ?: DEFAULT_SAMPLE_RATE
                                    val channels = delta.channels ?: DEFAULT_CHANNELS
                                    val newPlayer = GeminiAudioPlayer(context)
                                    val outputFile = File(context.cacheDir, "$fileName.wav")

                                    newPlayer.start(
                                        outputFile = outputFile,
                                        sampleRate = sampleRate,
                                        channels = channels,
                                        bitsPerSample = DEFAULT_BITS_PER_SAMPLE
                                    )

                                    player = newPlayer
                                    audioPlayer = newPlayer
                                    receivedAudio = true
                                }

                                player?.writeChunk(pcm)
                            }

                            line.isEmpty() -> {
                                currentEventType = null
                            }
                        }
                    }

                    if (!receivedAudio || player == null) {
                        return@withContext Result.failure(
                            Exception("No audio data received from Gemini")
                        )
                    }
                }

                val finalPlayer = player
                    ?: return@withContext Result.failure(
                        Exception("Audio player was not initialized")
                    )

                val outputFile = finalPlayer.finish()
                generationCompleted = true
                Result.success(outputFile)
            }
        } catch (e: CancellationException) {
            player?.stop()
            throw e
        } catch (e: Exception) {
            player?.stop()
            Result.failure(e)
        } finally {
            activeCall.set(null)
            if (!generationCompleted) {
                player?.stop()
            }
            audioPlayer = null
        }
    }

    fun stop() {
        activeCall.getAndSet(null)?.cancel()
        audioPlayer?.stop()
        audioPlayer = null
    }

    fun pause() {
        audioPlayer?.pause()
    }

    fun resume() {
        audioPlayer?.resume()
    }

    fun isGenerating(): Boolean = activeCall.get() != null
}