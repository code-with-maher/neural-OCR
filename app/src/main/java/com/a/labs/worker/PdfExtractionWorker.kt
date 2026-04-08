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

class PdfExtractionWorker(
    private val context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val channelId = "pdf_extraction_channel"
    private val notificationId = 1001

    override suspend fun doWork(): Result {
        setupNotificationChannel()
        setForeground(createForegroundInfo("جاري التجهيز..."))

        val bookId = inputData.getString("bookId") ?: return failWithMessage("رقم الكتاب مفقود")
        val targetStartPage = inputData.getInt("startPage", 1)
        val targetEndPage = inputData.getInt("endPage", 1)

        val db = AppDatabase.getDatabase(context)
        val repository = BookRepository(db.bookDao())
        val settings = SettingsManager(context)

        val apiKey = settings.geminiKey.first()
        val modelName = settings.geminiModel.first()
        val chunkSizeValue = settings.chunkSize.first()

        if (apiKey.isBlank()) return failWithMessage("مفتاح Gemini API مفقود، يرجى إضافته في الإعدادات.")

        val book = repository.getBookById(bookId) ?: return failWithMessage("لم يتم العثور على الكتاب في السجلات")
        val sourceUri = Uri.parse(book.sourcePdfUri)

        val chunker = PdfChunkerUseCase(context)
        val httpClient = OkHttpClient()
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
        }

        val systemPrompt = context.getString(R.string.system_prompt)
        val userPrompt = context.getString(R.string.user_prompt)

        for ((index, chunk) in chunks.withIndex()) {
            if (chunk.status == "COMPLETED") continue

            updateNotification("جاري معالجة الدفعة ${index + 1} من ${chunks.size}...")
            repository.updateChunkStatus(chunk.id, "PROCESSING", chunk.filesApiUri)

            var fileUri = chunk.filesApiUri

            if (fileUri == null) {
                val fileName = "chunk_${bookId}_${chunk.startPage}.pdf"
                val chunkResult = chunker.extractPdfChunk(sourceUri, chunk.startPage, chunk.endPage - chunk.startPage, fileName)
                val chunkFile = chunkResult.getOrNull() ?: return failWithMessage("فشل في تقسيم ملف PDF محلياً")

                val uploadResult =  filesClient.uploadPdfChunk(chunkFile)
                fileUri = uploadResult.getOrNull()

                if (fileUri == null) {
                    chunkFile.delete()
                    return failWithMessage("فشل في رفع الدفعة إلى خوادم Gemini")
                }

                repository.updateChunkStatus(chunk.id, "PROCESSING", fileUri)
                chunkFile.delete()
            }

            val ocrResult = ocrClient.extractTextFromPdfUri(fileUri, systemPrompt, userPrompt)
            val extractedData = ocrResult.getOrNull() ?: return failWithMessage("فشل في استخراج النص، تأكد من جودة الإنترنت")

            val pageEntities = extractedData.pages.map {
                val finalContent = if (it.markdownContent.trim().isEmpty()) "الصفحة فارغة" else it.markdownContent
                // الإصلاح الجذري: مطابقة الترقيم القادم من Gemini مع الترقيم الأصلي للكتاب
                val actualPageNumber = chunk.startPage + it.pageNumber 
                
                PageEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    pageNumber = actualPageNumber,
                    markdownContent = finalContent
                )
            }

            repository.insertPages(pageEntities)
            repository.updateChunkStatus(chunk.id, "COMPLETED", fileUri)
        }

        return Result.success()
    }

    private fun failWithMessage(msg: String): Result {
        return Result.failure(workDataOf("error" to msg))
    }

    private fun setupNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                "معالجة الكتب الذكية",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "يظهر أثناء استخراج النصوص من ملفات PDF"
            }
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