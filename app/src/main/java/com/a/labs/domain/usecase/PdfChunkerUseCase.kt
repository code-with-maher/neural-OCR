package com.a.labs.domain.usecase

import android.content.Context
import android.net.Uri
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.min

class PdfChunkerUseCase(private val context: Context) {

    init {
        PDFBoxResourceLoader.init(context)
    }

    suspend fun extractPdfChunk(
        sourceUri: Uri,
        startPage: Int,
        chunkSize: Int,
        outputFileName: String
    ): Result<File> = withContext(Dispatchers.IO) {
        var sourceDoc: PDDocument? = null
        var targetDoc: PDDocument? = null

        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalArgumentException("Cannot open URI")

            sourceDoc = PDDocument.load(inputStream)
            targetDoc = PDDocument()

            val totalPages = sourceDoc.numberOfPages
            if (startPage >= totalPages) {
                throw IllegalArgumentException("Start page exceeds total pages")
            }

            val endPage = min(startPage + chunkSize, totalPages)

            for (i in startPage until endPage) {
                val page = sourceDoc.getPage(i)
                targetDoc.addPage(page)
            }

            val outputFile = File(context.cacheDir, outputFileName)
            targetDoc.save(outputFile)

            Result.success(outputFile)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            targetDoc?.close()
            sourceDoc?.close()
        }
    }

    suspend fun getTotalPages(sourceUri: Uri): Result<Int> = withContext(Dispatchers.IO) {
        var sourceDoc: PDDocument? = null
        try {
            val inputStream = context.contentResolver.openInputStream(sourceUri)
                ?: throw IllegalArgumentException("Cannot open URI")
            sourceDoc = PDDocument.load(inputStream)
            Result.success(sourceDoc.numberOfPages)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            sourceDoc?.close()
        }
    }
}