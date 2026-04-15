package com.a.labs.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.a.labs.R
import com.a.labs.core.AppLogger
import com.a.labs.data.local.SettingsManager
import com.a.labs.data.local.room.AppDatabase
import com.a.labs.data.local.room.entity.ChunkEntity
import com.a.labs.data.local.room.entity.PageEntity
import com.a.labs.data.remote.api.GeminiFilesClient
import com.a.labs.data.remote.api.GeminiOcrClient
import com.a.labs.data.repository.BookRepository
import com.a.labs.domain.usecase.PdfChunkerUseCase
import kotlinx.coroutines.flow.first
import okhttp3.OkHttpClient
import java.util.UUID
import java.util.concurrent.TimeUnit

class PdfExtractionWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "pdf_extraction_channel"
    private val notificationId = 1001
    private var isLoggingEnabled = false

    override suspend fun doWork(): Result {
        setupNotificationChannel()
        setForeground(createForegroundInfo("جاري التجهيز..."))

        try {
            val settings = SettingsManager(context)
            isLoggingEnabled = settings.isLoggingEnabled.first()

            val bookId = inputData.getString("bookId") ?: return failWithMessage("رقم الكتاب مفقود")
            val targetStartPage = inputData.getInt("startPage", 1)
            val targetEndPage = inputData.getInt("endPage", 1)

            AppLogger.log(context, isLoggingEnabled, "بدء الاستخراج: $bookId من $targetStartPage إلى $targetEndPage")

            val db = AppDatabase.getDatabase(context)
            val repository = BookRepository(db.bookDao())
            val chunkSizeValue = settings.chunkSize.first()

            var chunks = repository.getChunksForBook(bookId)
            if (chunks.isEmpty()) {
                var currentStart = targetStartPage - 1 
                val finalEnd = targetEndPage 

                while (currentStart < finalEnd) {
                    val end = minOf(currentStart + chunkSizeValue, finalEnd)
                    val newChunk = ChunkEntity(
                        id = UUID.randomUUID().toString(),
                        bookId = bookId,
                        startPage = currentStart,
                        endPage = end,
                        status = "PENDING"
                    )
                    repository.insertChunk(newChunk)
                    currentStart = end
                }
                chunks = repository.getChunksForBook(bookId)
            }

            val apiKey = settings.geminiKey.first()
            if (apiKey.isBlank()) {
                chunks.forEach { repository.updateChunkStatus(it.id, "FAILED", it.filesApiUri, it.filesApiUriExpiration) }
                return failWithMessage("مفتاح Gemini مفقود، يرجى إضافته من الإعدادات.")
            }

            val modelName = settings.geminiModel.first()
            val book = repository.getBookById(bookId) ?: return failWithMessage("الكتاب غير موجود.")
            val sourceUri = Uri.parse(book.sourcePdfUri)

            val httpClient = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.MINUTES)
                .readTimeout(15, TimeUnit.MINUTES)
                .writeTimeout(15, TimeUnit.MINUTES)
                .build()

            val chunker = PdfChunkerUseCase(context)
            val filesClient =  GeminiFilesClient(httpClient, apiKey)
            val ocrClient = GeminiOcrClient(httpClient, apiKey, modelName)

            val systemPrompt = context.getString(R.string.system_prompt)
            val  userPrompt = context.getString(R.string.user_prompt)

            for ((index, chunk) in chunks.withIndex()) {
                if (chunk.status == "COMPLETED") continue

                try {
                    updateNotification("جاري معالجة الدفعة ${index + 1} من ${chunks.size}...")

                    val currentTime = System.currentTimeMillis()
                    var fileUri = chunk.filesApiUri
                    var expirationTime = chunk.filesApiUriExpiration

                    val isExpired = expirationTime != null && currentTime >= expirationTime
                    val needsUpload = fileUri == null || isExpired

                    if (needsUpload) {
                        val fileName = "chunk_${bookId}_${chunk.startPage}.pdf"
                        val chunkResult = chunker.extractPdfChunk(sourceUri, chunk.startPage, chunk.endPage - chunk.startPage, fileName)
                        val chunkFile = chunkResult.getOrNull() ?: throw Exception("فشل في تقسيم PDF محلياً.")

                        val uploadResult = filesClient.uploadPdfChunk(chunkFile)
                        val (newUri, newExpiration) = uploadResult.getOrNull() ?: run {
                            chunkFile.delete()
                            throw Exception("فشل الرفع. تأكد من الإنترنت.")
                        }

                        fileUri = newUri
                        expirationTime = newExpiration
                        repository.updateChunkStatus(chunk.id, "PROCESSING", fileUri, expirationTime)
                        chunkFile.delete()
                    } else {
                        repository.updateChunkStatus(chunk.id, "PROCESSING", fileUri, expirationTime)
                    }

                    val ocrResult = ocrClient.extractTextFromPdfUri(fileUri!!, systemPrompt, userPrompt)

                    if (ocrResult.isFailure) {
                        throw ocrResult.exceptionOrNull() ?: Exception("خطأ غير معروف من جيميناي")
                    }

                    val extractedData = ocrResult.getOrNull() ?: throw Exception("استجابة جيميناي فارغة.")

                    val sortedExtractedPages = extractedData.pages.sortedBy { it.pageNumber }
                    val pageEntities = sortedExtractedPages.mapIndexed { i, dto ->
                        val strictPageNumber = chunk.startPage + 1 + i 
                        PageEntity(
                            id = UUID.randomUUID().toString(),
                            bookId = bookId,
                            pageNumber = strictPageNumber,
                            markdownContent = if (dto.markdownContent.trim().isEmpty()) "الصفحة فارغة" else dto.markdownContent
                        )
                    }

                    repository.insertPages(pageEntities)
                    repository.updateChunkStatus(chunk.id, "COMPLETED", fileUri, expirationTime)

                } catch (e: Exception) {
                    val errorString = e.message ?: ""
                    val friendlyError = when {
                        errorString.contains("503") -> "خوادم الذكاء الاصطناعي عليها ضغط هائل حالياً. يرجى تغيير النموذج من الإعدادات أو إعادة المحاولة."
                        errorString.contains("404") -> "النموذج المختار غير مدعوم، يرجى تغييره من الإعدادات."
                        errorString.contains("timeout", ignoreCase = true) -> "انتهى وقت الاتصال (Timeout). يرجى التأكد من سرعة الإنترنت."
                        else -> "حدث خطأ غير متوقع: $errorString"
                    }

                    AppLogger.log(context, isLoggingEnabled, "خطأ بالدفعة ${index + 1}:\n$friendlyError\n${e.stackTraceToString()}")
                    repository.updateChunkStatus(chunk.id, "FAILED", chunk.filesApiUri, chunk.filesApiUriExpiration)
                    return failWithMessage(friendlyError)
                }
            }

            notificationManager.cancel(notificationId)
            return Result.success()

         } catch (e: Exception) {
            val fatalMsg = e.message ?: "خطأ فادح"
            return failWithMessage(fatalMsg)
        }
    }

    private suspend fun failWithMessage(msg: String): Result {
        notificationManager.cancel(notificationId)
         AppLogger.log(context, isLoggingEnabled, "توقف Worker بسبب: $msg")
        return Result.failure(workDataOf("error" to msg))
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "معالجة الكتب الذكية",
                NotificationManager.IMPORTANCE_LOW
            )
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createForegroundInfo(progressText: String): ForegroundInfo {
        val appName = context.getString(R.string.app_name)
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(appName)
            .setContentText(progressText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(notificationId, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(notificationId, notification)
        }
    }

    private fun updateNotification(progressText: String) {
        val appName = context.getString(R.string.app_name)
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle(appName)
            .setContentText(progressText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        notificationManager.notify(notificationId, notification)
      }
}
