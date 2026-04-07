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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

class LibraryViewModel(
    private val repository: BookRepository,
    private val chunkerUseCase: PdfChunkerUseCase
) : ViewModel() {

    val books: StateFlow<List<BookEntity>> = repository.getAllBooks()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun addBook(context: Context, uri: Uri, customTitle: String? = null) {
        viewModelScope.launch {
            val totalPagesResult = chunkerUseCase.getTotalPages(uri)
            val totalPages = totalPagesResult.getOrNull() ?: 0
            val bookId = UUID.randomUUID().toString()
            val title = customTitle ?: "كتاب جديد"

            val newBook = BookEntity(
                id = bookId,
                title = title,
                sourcePdfUri = uri.toString(),
                totalPages = totalPages
            )

            repository.insertBook(newBook)
            startExtractionWork(context, bookId)
        }
    }

    private fun startExtractionWork(context: Context, bookId: String) {
        val workRequest = OneTimeWorkRequestBuilder<PdfExtractionWorker>()
            .setInputData(workDataOf("bookId" to bookId))
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    fun deleteBook(bookId: String) {
        viewModelScope.launch {
            repository.deleteBook(bookId)
        }
    }
}