package com.a.labs.data.remote.api

import com.a.labs.data.remote.dto.Content
import com.a.labs.data.remote.dto.FileData
import com.a.labs.data.remote.dto.GeminiRequest
import com.a.labs.data.remote.dto.GeminiResponse
import com.a.labs.data.remote.dto.GenerationConfig
import com.a.labs.data.remote.dto.OcrResultDto
import com.a.labs.data.remote.dto.Part
import com.a.labs.data.remote.dto.SystemInstruction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class GeminiOcrClient(
    private val client: OkHttpClient,
    private val apiKey: String,
    private val modelName: String
) {
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
        isLenient = true
    }

    suspend fun extractTextFromPdfUri(
        fileUri: String,
        systemPrompt: String,
        userPrompt: String
    ): Result<OcrResultDto> = withContext(Dispatchers.IO) {
        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelName:generateContent?key=$apiKey"

            val schema = buildJsonObject {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("pages") {
                        put("type", "array")
                        putJsonObject("items") {
                            put("type", "object")
                            putJsonObject("properties") {
                                putJsonObject("pageNumber") { put("type", "integer") }
                                putJsonObject("markdownContent") { put("type", "string") }
                            }
                            putJsonArray("required") {
                                add("pageNumber")
                                add("markdownContent")
                            }
                        }
                    }
                }
                putJsonArray("required") { add("pages") }
            }

            val requestBodyDto = GeminiRequest(
                systemInstruction = SystemInstruction(parts = listOf(Part(text = systemPrompt))),
                contents = listOf(
                    Content(
                        role = "user",
                        parts = listOf(
                            Part(fileData = FileData(mimeType = "application/pdf", fileUri = fileUri)),
                            Part(text = userPrompt)
                        )
                    )
                ),
                generationConfig = GenerationConfig(
                    responseMimeType = "application/json",
                    responseSchema = schema
                )
            )

            val jsonBody = jsonConfig.encodeToString(requestBodyDto)
            val requestBody = jsonBody.toRequestBody("application/json".toMediaType())

            val request = Request.Builder().url(url).post(requestBody).build()
            val response = client.newCall(request).execute()
            val responseString = response.body?.string() ?: ""

            if (response.isSuccessful && responseString.isNotEmpty()) {
                val geminiResponse = jsonConfig.decodeFromString<GeminiResponse>(responseString)
                val rawJsonText = geminiResponse.candidates?.firstOrNull()?.content?.parts?.firstOrNull()?.text

                if (rawJsonText != null) {
                    val cleanJson = rawJsonText.replace(Regex("```json\n?|```"), "").trim()
                    val ocrResult =   jsonConfig.decodeFromString<OcrResultDto>(cleanJson)
                    Result.success(ocrResult)
                } else {
                    Result.failure(Exception("استجابة Gemini فارغة أو لا تحتوي على نص."))
                }
            } else {
                Result.failure(Exception("فشل الاتصال: ${response.code} - $responseString"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
      }
}