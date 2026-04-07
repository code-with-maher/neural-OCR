package com.a.labs.worker

import android.content.Context
import android.net.Uri
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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

    override suspend fun doWork(): Result {
        val bookId = inputData.getString("bookId") ?: return Result.failure()

        val db = AppDatabase.getDatabase(context)
        val repository = BookRepository(db.bookDao())
        val settings = SettingsManager(context)

        val apiKey = settings.geminiKey.first()
        val modelName = settings.geminiModel.first()
        val chunkSizeValue = settings.chunkSize.first()

        if (apiKey.isBlank()) return Result.failure()

        val book = repository.getBookById(bookId) ?: return Result.failure()
        val sourceUri = Uri.parse(book.sourcePdfUri)

        val chunker = PdfChunkerUseCase(context)
        val httpClient = OkHttpClient()
        val filesClient = GeminiFilesClient(httpClient, apiKey)
        val ocrClient = GeminiOcrClient(httpClient, apiKey, modelName)

        var chunks = repository.getChunksForBook(bookId)

        if (chunks.isEmpty()) {
            val totalPagesResult = chunker.getTotalPages(sourceUri)
            val totalPages = totalPagesResult.getOrNull() ?: return Result.failure()

            var currentStart = 0
            while (currentStart < totalPages) {
                val end = minOf(currentStart + chunkSizeValue, totalPages)
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

        for (chunk in chunks) {
            if (chunk.status == "COMPLETED") continue

            repository.updateChunkStatus(chunk.id, "PROCESSING", chunk.filesApiUri)

            var fileUri = chunk.filesApiUri

            if (fileUri == null) {
                val fileName = "chunk_${bookId}_${chunk.startPage}.pdf"
                val chunkResult = chunker.extractPdfChunk(sourceUri, chunk.startPage, chunkSizeValue, fileName)
                val chunkFile = chunkResult.getOrNull() ?: return Result.retry()

                val uploadResult = filesClient.uploadPdfChunk(chunkFile)
                fileUri = uploadResult.getOrNull()
                
                if (fileUri == null) {
                    chunkFile.delete()
                    return Result.retry()
                }
                
                repository.updateChunkStatus(chunk.id, "PROCESSING", fileUri)
                chunkFile.delete()
            }

            val ocrResult = ocrClient.extractTextFromPdfUri(fileUri, systemPrompt, userPrompt)
            val extractedData = ocrResult.getOrNull() ?: return Result.retry()

            val pageEntities = extractedData.pages.map {
                PageEntity(
                    id = UUID.randomUUID().toString(),
                    bookId = bookId,
                    pageNumber = it.pageNumber,
                    markdownContent =  it.markdownContent
                )
            }

            repository.insertPages(pageEntities)
            repository.updateChunkStatus(chunk.id, "COMPLETED", fileUri)
        }

        return Result.success()
     }
}