package com.a.labs.data.remote.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File

@Serializable
data class FileUploadResponse(
    val file: UploadedFile? = null
)

@Serializable
data class UploadedFile(
    val name: String? = null,
    val uri: String? = null,
    val mimeType: String? = null
)

class GeminiFilesClient(
    private val client: OkHttpClient,
    private val apiKey: String
) {
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
    }

    suspend fun uploadPdfChunk(pdfFile: File): Result<String> = withContext(Dispatchers.IO) {
        try {
            val url = "https://generativelanguage.googleapis.com/upload/v1beta/files?uploadType=media&key=$apiKey"
            val mediaType = "application/pdf".toMediaType()
            val requestBody = pdfFile.asRequestBody(mediaType)

            val request = Request.Builder()
                .url(url)
                .post(requestBody)
                .build()

            val response = client.newCall(request).execute()
            val responseString = response.body.string()

            if (response.isSuccessful) {
                val uploadResponse = jsonConfig.decodeFromString<FileUploadResponse>(responseString)
                val fileUri = uploadResponse.file?.uri

                if (fileUri != null) {
                    Result.success(fileUri)
                } else {
                    Result.failure(Exception("Upload succeeded but URI is null"))
                }
            } else {
                Result.failure(Exception("Upload Error: ${response.code} - $responseString"))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}