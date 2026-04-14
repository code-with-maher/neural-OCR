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
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

@Serializable
data class FileUploadResponse(
    val file: UploadedFile? = null
)

@Serializable
data class UploadedFile(
    val name: String? = null,
    val uri: String? = null,
    val mimeType: String? = null,
    val expirationTime: String? = null
)

class GeminiFilesClient(
    private val client: OkHttpClient,
    private val apiKey: String
) {
    private val jsonConfig = Json {
        ignoreUnknownKeys = true
    }

    suspend fun uploadPdfChunk(pdfFile: File): Result<Pair<String, Long>> = withContext(Dispatchers.IO) {
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
                val expirationTimeStr = uploadResponse.file?.expirationTime

                if (fileUri != null) {
                    val expirationMillis = parseExpirationTime(expirationTimeStr)
                    Result.success(Pair(fileUri, expirationMillis))
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

    private fun parseExpirationTime(expirationTimeStr: String?): Long {
        if (expirationTimeStr == null) return 0L
        return try {
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSSSS'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            val date = format.parse(expirationTimeStr)
            date?.time ?: 0L
        } catch (e: Exception) {
            try {
                val fallbackFormat = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                fallbackFormat.timeZone = TimeZone.getTimeZone("UTC")
                val date = fallbackFormat.parse(expirationTimeStr)
                date?.time ?: 0L
            } catch (ex: Exception) {
                0L
            }
        }
    }
}