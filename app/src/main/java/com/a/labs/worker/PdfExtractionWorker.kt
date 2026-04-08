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

            AppLogger.log(context, isLoggingEnabled, "بدء الاستخراج للكتاب: $bookId من صفحة $targetStartPage إلى $targetEndPage")

            val db = AppDatabase.getDatabase(context)
            val repository = BookRepository(db.bookDao())

            val apiKey = settings.geminiKey.first()
            val modelName = settings.geminiModel.first()
            val chunkSizeValue = settings.chunkSize.first()

            if (apiKey.isBlank()) return failWithMessage("مفتاح Gemini API مفقود.")

            val book = repository.getBookById(bookId) ?: return failWithMessage("الكتاب غير موجود في السجلات.")
            val sourceUri = Uri.parse(book.sourcePdfUri)

            // حل مشكلة الـ Timeout: إعطاء مهلة دقيقتين لتجنب فشل الدفعات الكبيرة
            val httpClient = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            val chunker = PdfChunkerUseCase(context)
            val filesClient = GeminiFilesClient(httpClient, apiKey)
            val ocrClient = GeminiOcrClient(httpClient, apiKey, modelName)

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
                AppLogger.log(context, isLoggingEnabled, "تم تقسيم الكتاب إلى ${chunks.size} دفعة.")
            }

            val systemPrompt =  context.getString(R.string.system_prompt)
            val userPrompt = context.getString(R.string.user_prompt)

            for ((index, chunk) in chunks.withIndex()) {
                if (chunk.status == "COMPLETED") continue

                try {
                    val progressMsg = "جاري معالجة الدفعة ${index + 1} من ${chunks.size}..."
                    updateNotification(progressMsg)
                    AppLogger.log(context, isLoggingEnabled, progressMsg)
                    
                    repository.updateChunkStatus(chunk.id, "PROCESSING", chunk.filesApiUri)
                    var fileUri = chunk.filesApiUri

                    if (fileUri == null) {
                        AppLogger.log(context, isLoggingEnabled, "تقسيم ملف PDF محلياً...")
                        val fileName = "chunk_${bookId}_${chunk.startPage}.pdf"
                        val chunkResult = chunker.extractPdfChunk(sourceUri, chunk.startPage, chunk.endPage - chunk.startPage, fileName)
                        val chunkFile = chunkResult.getOrNull() ?: throw Exception("فشل في التقسيم المحلي.")

                        AppLogger.log(context, isLoggingEnabled, "رفع الدفعة إلى خوادم جيميناي...")
                        val uploadResult = filesClient.uploadPdfChunk(chunkFile)
                        fileUri = uploadResult.getOrNull()

                        if (fileUri == null) {
                            chunkFile.delete()
                            throw Exception("فشل الرفع لجيميناي. تأكد من الإنترنت.")
                        }

                        AppLogger.log(context, isLoggingEnabled, "تم الرفع بنجاح. URI: $fileUri")
                        repository.updateChunkStatus(chunk.id, "PROCESSING", fileUri)
                        chunkFile.delete()
                    } else {
                        AppLogger.log(context, isLoggingEnabled, "استخدام URI محفوظ مسبقاً: $fileUri")
                    }

                    AppLogger.log(context, isLoggingEnabled, "إرسال طلب الاستخراج (OCR)...")
                    val ocrResult = ocrClient.extractTextFromPdfUri(fileUri, systemPrompt, userPrompt)
                    
                    if (ocrResult.isFailure) {
                        throw Exception("فشل الاستخراج من جيميناي: ${ocrResult.exceptionOrNull()?.message}")
                    }
                    
                    val extractedData = ocrResult.getOrNull() ?: throw Exception("استجابة جيميناي فارغة.")
                    AppLogger.log(context, isLoggingEnabled, "تم استلام الرد بنجاح.")

                    val sortedExtractedPages = extractedData.pages.sortedBy { it.pageNumber }
                    val pageEntities = sortedExtractedPages.mapIndexed { i, dto ->
                        val finalContent = if (dto.markdownContent.trim().isEmpty()) "الصفحة فارغة" else dto.markdownContent
                        val strictPageNumber = chunk.startPage + 1 + i 
                        PageEntity(
                            id = UUID.randomUUID().toString(),
                            bookId = bookId,
                            pageNumber = strictPageNumber,
                            markdownContent = finalContent
                        )
                    }

                    repository.insertPages(pageEntities)
                    repository.updateChunkStatus(chunk.id, "COMPLETED", fileUri)
                    AppLogger.log(context, isLoggingEnabled, "اكتملت الدفعة ${index + 1}.")

                } catch (e: Exception) {
                    val errorMsg = e.message ?: "خطأ غير معروف"
                    AppLogger.log(context, isLoggingEnabled, "حدث خطأ بالدفعة ${index + 1}:\n$errorMsg\n${e.stackTraceToString()}")
                    repository.updateChunkStatus(chunk.id, "FAILED", chunk.filesApiUri)
                    return failWithMessage("خطأ في الدفعة ${index + 1}: $errorMsg")
                }
            }

            AppLogger.log(context, isLoggingEnabled, "تمت المعالجة بالكامل بنجاح.")
             notificationManager.cancel(notificationId) // إخفاء الإشعار
            return Result.success()

        } catch (e: Exception) {
            val fatalMsg = e.message ?: "خطأ فادح"
            AppLogger.log(context, isLoggingEnabled, "فشل ذريع بالوركر:\n$fatalMsg\n${e.stackTraceToString()}")
            return failWithMessage(fatalMsg)
        }
    }

    private suspend fun failWithMessage(msg: String): Result {
        notificationManager.cancel(notificationId) // حل جذري: إخفاء الإشعار عند الفشل
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
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("ALabs AI")
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
        val notification = NotificationCompat.Builder(context, channelId)
            .setContentTitle("ALabs AI")
            .setContentText(progressText)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .setProgress(0, 0, true)
            .build()
        notificationManager.notify(notificationId, notification)
     }
}