package com.a.labs.core

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object AppLogger {
    private const val LOG_FILE_NAME = "app_debug_logs.txt"

    suspend fun log(context: Context, isEnabled: Boolean, message: String) = withContext(Dispatchers.IO) {
        if (!isEnabled) return@withContext
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
            val logEntry = "[$time] $message\n\n"
            file.appendText(logEntry)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    suspend fun getLogs(context: Context): List<String> = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) {
                file.readText().split("\n\n").filter { it.isNotBlank() }
            } else emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun clearLogs(context: Context) = withContext(Dispatchers.IO) {
        try {
            val file = File(context.filesDir, LOG_FILE_NAME)
            if (file.exists()) file.delete()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}