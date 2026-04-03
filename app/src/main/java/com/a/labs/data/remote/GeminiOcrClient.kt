package com.a.labs.data.remote

import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

data class GeminiFileResponse(@SerializedName("file") val file: GeminiFile)
data class GeminiFile(@SerializedName("uri") val uri: String, @SerializedName("name") val name: String)
data class OCRPageResponse(
    @SerializedName("page_number") val pageNumber: Int,
    @SerializedName("content") val content: String
)

class GeminiOcrClient(private val context: Context, private val client: OkHttpClient) {
    private val gson = Gson()
    private val jsonMediaType = "application/json".toMediaType()

    private val systemInstruction = """
        أنت خبير محترف في تحليل مستندات PDF واستخراج المحتوى (OCR) بدقة متناهية. 
        مهمتك هي استخراج النص الكامل من الصفحات المحددة وتحويله إلى صيغة Markdown منسقة.
        
        القواعد الصارمة للمعالجة:
        1. استخراج النص: استخرج كافة النصوص الموجودة في الصفحة مع الحفاظ على الترتيب المنطقي للقراءة.
        2. العناصر المرئية: في حال وجود صور، رسومات بيانية، أو مخططات، يجب عليك كتابة وصف تفصيلي للمحتوى المرئي داخل علامات [صورة: وصف الصورة هنا].
        3. الجداول: قم بتحويل أي جدول موجود في الصفحة إلى صيغة جداول Markdown بدقة، مع الحفاظ على العناوين والبيانات.
        4. التنسيق: استخدم عناوين Markdown (#, ##, ###) للترويسات، والقوائم المنقطة للعناصر، والخط العريض للكلمات الهامة.
        5. الدقة: لا تقم بتلخيص المحتوى، استخرجه كما هو موجود حرفياً.
        6. المخرجات: يجب أن تكون الإجابة عبارة عن مصفوفة JSON تحتوي على رقم الصفحة والمحتوى بصيغة Markdown فقط.
    """.trimIndent()

    fun getPdfPageCount(uri: Uri): Int {
        return try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
            val renderer = PdfRenderer(pfd!!)
            val count = renderer.pageCount
            renderer.close()
            pfd.close()
            count
        } catch (e: Exception) { 0 }
    }

    suspend fun uploadPdfToGemini(uri: Uri, apiKey: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return@withContext Result.failure(Exception("Failed to read file"))
            
            val startRequest = Request.Builder()
                .url("https://generativelanguage.googleapis.com/upload/v1beta/files?key=$apiKey")
                .addHeader("X-Goog-Upload-Protocol", "resumable")
                .addHeader("X-Goog-Upload-Command", "start")
                .addHeader("X-Goog-Upload-Header-Content-Length", bytes.size.toString())
                .addHeader("X-Goog-Upload-Header-Content-Type", "application/pdf")
                .post("{\"file\": {\"display_name\": \"book_${System.currentTimeMillis()}\"}}".toRequestBody(jsonMediaType))
                .build()

            val uploadUrl = client.newCall(startRequest).execute().header("X-Goog-Upload-Url") 
                ?: return@withContext Result.failure(Exception("Failed to get upload URL"))

            val finalizeRequest = Request.Builder()
                .url(uploadUrl)
                .addHeader("X-Goog-Upload-Command", "upload, finalize")
                .addHeader("X-Goog-Upload-Offset", "0")
                .post(bytes.toRequestBody("application/pdf".toMediaType()))
                .build()

            client.newCall(finalizeRequest).execute().use { response ->
                val body = response.body?.string()
                val fileRes = gson.fromJson(body, GeminiFileResponse::class.java)
                Result.success(fileRes.file.uri)
            }
        } catch (e: Exception) { Result.failure(e) }
    }

    suspend fun processPages(fileUri: String, apiKey: String, modelId: String, startPage: Int, endPage: Int): Result<List<OCRPageResponse>> = withContext(Dispatchers.IO) {
        try {
            val prompt = "قم بمعالجة الصفحات من $startPage إلى $endPage من ملف الـ PDF المرفق بدقة وحولها إلى Markdown."
            
            val requestBody = mapOf(
                "system_instruction" to mapOf("parts" to listOf(mapOf("text" to systemInstruction))),
                "contents" to listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to prompt),
                            mapOf("file_data" to mapOf("mime_type" to "application/pdf", "file_uri" to fileUri))
                        )
                    )
                ),
                "generationConfig" to mapOf(
                    "response_mime_type" to "application/json",
                    "response_schema" to mapOf(
                        "type" to "ARRAY",
                        "items" to mapOf(
                            "type" to "OBJECT",
                            "properties" to mapOf(
                                "page_number" to mapOf("type" to "INTEGER"),
                                "content" to mapOf("type" to "STRING")
                            ),
                            "required" to listOf("page_number", "content")
                        )
                    )
                )
            )

            val request = Request.Builder()
                .url("https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent?key=$apiKey")
                .post(gson.toJson(requestBody).toRequestBody(jsonMediaType))
                .build()

            client.newCall(request).execute().use { response ->
                val responseBody = response.body?.string() ?: ""
                val jsonRes = JSONObject(responseBody)
                val textResponse = jsonRes.getJSONArray("candidates")
                    .getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .getJSONObject(0)
                    .getString("text")

                val pagesList: List<OCRPageResponse> = gson.fromJson(textResponse, object : com.google.gson.reflect.TypeToken<List<OCRPageResponse>>() {}.type)
                Result.success(pagesList)
            }
        } catch (e: Exception) { Result.failure(e) }
    }
}
