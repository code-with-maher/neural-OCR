package com.a.labs.data.remote.api

import android.content.Context
import android.util.Base64
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
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.nio.ByteBuffer
import java.nio.ByteOrder

@Serializable
private data class InteractionRequest(
    val model: String,
    val input: String,
    val response_format: AudioResponseFormat,
    val generation_config: GenerationConfig,
    val stream: Boolean = true
)

@Serializable
private data class AudioResponseFormat(
    val type: String = "audio",
    val mime_type: String = "audio/l16",
    val delivery: String = "inline"
)

@Serializable
private data class GenerationConfig(
    val speech_config: List<SpeechConfig>
)

@Serializable
private data class SpeechConfig(
    val voice: String
)

@Serializable
private data class SseEvent(
    val event_type: String? = null,
    val index: Int? = null,
    val delta: AudioDelta? = null
)

@Serializable
private data class AudioDelta(
    val type: String? = null,
    val data: String? = null,
    val mime_type: String? = null,
    val sample_rate: Int? = null,
    val channels: Int? = null
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

    companion object {
        private const val DEFAULT_MODEL = "gemini-3.1-flash-tts-preview"
        private const val SAMPLE_RATE = 24_000
        private const val CHANNELS = 1
        private const val BITS_PER_SAMPLE = 16
    }

    /**
     * Generates speech through the Gemini Interactions API.
     *
     * Audio is streamed as PCM chunks while the model is generating.
     *
     * @param text Text to synthesize.
     * @param fileName Name of the resulting WAV file without extension.
     * @param voiceName Gemini prebuilt voice name.
     * @param onAudioChunk Called immediately whenever a PCM audio chunk arrives.
     *
     * @return The final WAV file after the complete stream finishes.
     */
    suspend fun generateSpeech(
        text: String,
        fileName: String,
        voiceName: String = "Aoede",
        onAudioChunk: (ByteArray) -> Unit = {}
    ): Result<File> = withContext(Dispatchers.IO) {

        val pcmChunks = ArrayList<ByteArray>()
        var totalPcmSize = 0

        try {
            val url =
                "https://generativelanguage.googleapis.com/v1beta/interactions"

            val requestBodyDto = InteractionRequest(
                model = GeminiModels.TTS_MODEL.ifBlank {
                    DEFAULT_MODEL
                },
                input = text,
                response_format = AudioResponseFormat(
                    type = "audio",
                    mime_type = "audio/l16",
                    delivery = "inline"
                ),
                generation_config = GenerationConfig(
                    speech_config = listOf(
                        SpeechConfig(
                            voice = voiceName
                        )
                    )
                ),
                stream = true
            )

            val jsonBody = jsonConfig.encodeToString(requestBodyDto)

            val request = Request.Builder()
                .url(url)
                .header("x-goog-api-key", apiKey)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .post(
                    jsonBody.toRequestBody(
                        "application/json".toMediaType()
                    )
                )
                .build()

            client.newCall(request).execute().use { response ->

                if (!response.isSuccessful) {
                    val errorBody = response.body?.string().orEmpty()

                    return@withContext Result.failure(
                        Exception(
                            "Gemini Interactions API error: " +
                                "${response.code} - $errorBody"
                        )
                    )
                }

                val responseBody = response.body
                    ?: return@withContext Result.failure(
                        Exception("Empty response body from Gemini")
                    )

                BufferedReader(
                    InputStreamReader(
                        responseBody.byteStream(),
                        Charsets.UTF_8
                    )
                ).use { reader ->

                    var currentEventType: String? = null

                    while (true) {
                        val line = reader.readLine() ?: break

                        /*
                         * SSE format:
                         *
                         * event: step.delta
                         * data: {...}
                         *
                         * data: [DONE]
                         */

                        when {
                            line.startsWith("event:") -> {
                                currentEventType =
                                    line.substringAfter("event:")
                                        .trim()
                            }

                            line.startsWith("data:") -> {
                                val data =
                                    line.substringAfter("data:")
                                        .trim()

                                if (data.isEmpty() || data == "[DONE]") {
                                    continue
                                }

                                try {
                                    val event =
                                        jsonConfig.decodeFromString<SseEvent>(
                                            data
                                        )

                                    /*
                                     * Audio arrives as:
                                     *
                                     * event_type = step.delta
                                     * delta.type = audio
                                     * delta.data = BASE64 PCM
                                     */
                                    if (
                                        currentEventType == "step.delta" &&
                                        event.delta?.type == "audio"
                                    ) {
                                        val base64Audio =
                                            event.delta.data

                                        if (!base64Audio.isNullOrEmpty()) {

                                            val audioBytes =
                                                Base64.decode(
                                                    base64Audio,
                                                    Base64.DEFAULT
                                                )

                                            if (audioBytes.isNotEmpty()) {

                                                /*
                                                 * Keep a copy for the
                                                 * final WAV file.
                                                 */
                                                pcmChunks.add(audioBytes)
                                                totalPcmSize += audioBytes.size

                                                /*
                                                 * Immediately deliver the
                                                 * PCM chunk to the playback
                                                 * layer.
                                                 */
                                                onAudioChunk(audioBytes)
                                            }
                                        }
                                    }

                                } catch (e: Exception) {
                                    /*
                                     * Ignore malformed/non-audio SSE
                                     * events. The stream contains many
                                     * event types that are irrelevant to
                                     * audio playback.
                                     */
                                }
                            }

                            line.isEmpty() -> {
                                /*
                                 * End of one SSE event.
                                 */
                                currentEventType = null
                            }
                        }
                    }
                }

                if (totalPcmSize == 0) {
                    return@withContext Result.failure(
                        Exception("No audio data received from Gemini")
                    )
                }

                /*
                 * The streaming API gives us raw PCM.
                 *
                 * We assemble all chunks only after streaming has
                 * completed, then write one valid WAV file.
                 */
                val outputFile =
                    File(context.cacheDir, "$fileName.wav")

                savePcmAsWav(
                    pcmChunks = pcmChunks,
                    totalPcmSize = totalPcmSize,
                    file = outputFile,
                    sampleRate = SAMPLE_RATE,
                    channels = CHANNELS
                )

                Result.success(outputFile)
            }

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Writes streamed PCM chunks into a standard WAV file.
     */
    private fun savePcmAsWav(
        pcmChunks: List<ByteArray>,
        totalPcmSize: Int,
        file: File,
        sampleRate: Int,
        channels: Int
    ) {
        val bitsPerSample = BITS_PER_SAMPLE

        val byteRate =
            sampleRate *
                channels *
                bitsPerSample / 8

        val blockAlign =
            channels *
                bitsPerSample / 8

        val totalFileSize = totalPcmSize + 36

        val header = ByteBuffer
            .allocate(44)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply {
                put("RIFF".toByteArray(Charsets.US_ASCII))
                putInt(totalFileSize)

                put("WAVE".toByteArray(Charsets.US_ASCII))

                put("fmt ".toByteArray(Charsets.US_ASCII))
                putInt(16)

                // PCM format
                putShort(1.toShort())

                putShort(channels.toShort())
                putInt(sampleRate)
                putInt(byteRate)
                putShort(blockAlign.toShort())
                putShort(bitsPerSample.toShort())

                put("data".toByteArray(Charsets.US_ASCII))
                putInt(totalPcmSize)
            }
            .array()

        FileOutputStream(file).use { output ->

            output.write(header)

            for (chunk in pcmChunks) {
                output.write(chunk)
            }
        }
    }
}