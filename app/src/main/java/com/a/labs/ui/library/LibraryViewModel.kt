package com.a.labs.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.a.labs.data.local.room.entity.BookEntity
import com.a.labs.data.repository.BookRepository
import com.a.labs.domain.usecase.PdfChunkerUseCase
import com.a.labs.worker.PdfExtractionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class LibraryViewModel(
    private val repository: BookRepository,
    private val chunkerUseCase: PdfChunkerUseCase
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = repository.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() { _errorMessage.value = null }

    fun addBook(context: Context, uri: Uri, customTitle: String? = null) {
        viewModelScope.launch {
            try {
                // الحل العبقري: نسخ الملف محلياً لتجنب فقدان صلاحية القراءة في الخلفية
                val localFile = withContext(Dispatchers.IO) {
                    val file = File(context.filesDir, "book_${UUID.randomUUID()}.pdf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        file.outputStream().use { output -> input.copyTo(output) }
                    }
                    file
                }

                val safeUri = Uri.fromFile(localFile)
                val totalPagesResult = chunkerUseCase.getTotalPages(safeUri)

                if (totalPagesResult.isFailure) {
                    _errorMessage.value = "تعذر قراءة ملف PDF. قد يكون محمياً بكلمة مرور أو معطوباً."
                    return@launch
                }

                val totalPages = totalPagesResult.getOrNull() ?: 0
                if (totalPages == 0) {
                    _errorMessage.value = "ملف PDF يبدو فارغاً."
                    return@launch
                }

                val bookId = UUID.randomUUID().toString()
                val title = customTitle ?: "كتاب جديد"

                val newBook = BookEntity(
                    id = bookId,
                    title = title,
                    sourcePdfUri = safeUri.toString(),
                    totalPages = totalPages
                )

                repository.insertBook(newBook)
                startExtractionWork(context, bookId)

            } catch (e: Exception) {
                _errorMessage.value = "حدث خطأ غير متوقع أثناء إضافة الكتاب: ${e.localizedMessage}"
            }
        }
    }

    private fun startExtractionWork(context: Context, bookId: String) {
        val workRequest = OneTimeWorkRequestBuilder<PdfExtractionWorker>()
            .setInputData(workDataOf("bookId" to bookId))
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch { repository.deleteBook(bookId) }
    }
}