package com.a.labs.data.audio

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

class AudioExporter(
    private val context: Context
) {
    suspend fun exportAudio(
        sourceFile: File,
        bookTitle: String,
        pageNumber: Int
    ): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!sourceFile.exists() || sourceFile.length() == 0L) {
                return@withContext Result.failure(IllegalStateException("ملف الصوت غير موجود أو فارغ."))
            }

            val cleanTitle = bookTitle.trim().replace("\\s+".toRegex(), "_")
            val fileName = "${cleanTitle}_page_$pageNumber.wav"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val contentValues = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                    put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }

                val resolver = context.applicationContext.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                    ?: return@withContext Result.failure(IllegalStateException("فشل في إنشاء إدخال التنزيلات."))

                resolver.openOutputStream(uri)?.use { outputStream ->
                    FileInputStream(sourceFile).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: return@withContext Result.failure(IllegalStateException("فشل في فتح مسار الحفظ."))

                Result.success(fileName)
            } else {
                @Suppress("DEPRECATION")
                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }
                val destinationFile = File(downloadsDir, fileName)

                FileInputStream(sourceFile).use { inputStream ->
                    FileOutputStream(destinationFile).use { outputStream ->
                        inputStream.copyTo(outputStream)
                    }
                }

                Result.success(destinationFile.absolutePath)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}