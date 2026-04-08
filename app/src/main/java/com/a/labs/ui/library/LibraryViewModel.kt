package com.a.labs.ui.library

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.a.labs.data.local.room.entity.BookEntity
import com.a.labs.data.repository.BookRepository
import com.a.labs.domain.usecase.PdfChunkerUseCase
import com.a.labs.worker.PdfExtractionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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

    private val _showRangeDialog = MutableStateFlow(false)
    val showRangeDialog: StateFlow<Boolean> = _showRangeDialog.asStateFlow()

    private val _showProgressDialog = MutableStateFlow(false)
    val showProgressDialog: StateFlow<Boolean> = _showProgressDialog.asStateFlow()

    var pendingFileUri: Uri? = null
    var pendingTotalPages: Int = 0
    var pendingBookId: String = ""

    private val _readyToNavigateBookId = MutableStateFlow<String?>(null)
    val readyToNavigateBookId: StateFlow<String?> = _readyToNavigateBookId.asStateFlow()

    fun clearError() { _errorMessage.value = null }
    fun dismissRangeDialog() { _showRangeDialog.value = false }
    fun onNavigated() { _readyToNavigateBookId.value = null }

    fun prepareBook(context: Context, uri: Uri) {
        viewModelScope.launch {
            try {
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
                    _errorMessage.value = "تعذر قراءة ملف PDF. قد يكون محمياً أو معطوباً."
                    return@launch
                }

                val totalPages = totalPagesResult.getOrNull() ?: 0
                if (totalPages == 0) {
                    _errorMessage.value = "ملف PDF يبدو فارغاً."
                    return@launch
                }

                pendingFileUri = safeUri
                pendingTotalPages = totalPages
                pendingBookId = UUID.randomUUID().toString()
                _showRangeDialog.value = true

            } catch (e: Exception) {
                _errorMessage.value = "خطأ أثناء التهيئة: ${e.localizedMessage}"
            }
        }
    }

    fun startExtraction(context: Context, title: String, startPage: Int, endPage: Int) {
        _showRangeDialog.value = false
        _showProgressDialog.value = true

        viewModelScope.launch {
            try {
                val newBook = BookEntity(
                    id = pendingBookId,
                    title = title.ifBlank { "كتاب جديد" },
                    sourcePdfUri = pendingFileUri.toString(),
                    totalPages = pendingTotalPages,
                     lastReadPage = startPage
                )
                repository.insertBook(newBook)

                val constraints = Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

                val workRequest = OneTimeWorkRequestBuilder<PdfExtractionWorker>()
                    .setConstraints(constraints)
                    .setInputData(
                        workDataOf(
                            "bookId" to pendingBookId,
                            "startPage" to startPage,
                            "endPage" to endPage
                        )
                    )
                    .build()

                val workManager = WorkManager.getInstance(context)
                workManager.enqueue(workRequest)

                monitorExtractionProgress(context, workRequest.id, pendingBookId, startPage)

            } catch (e: Exception) {
                _showProgressDialog.value = false
                _errorMessage.value = "فشل بدء المعالجة: ${e.localizedMessage}"
            }
        }
    }

    private fun monitorExtractionProgress(context: Context, workId: UUID, bookId: String, targetStartPage: Int) {
        viewModelScope.launch {
            var isNavigated = false
            
            repository.getPagesForBook(bookId).collect { pages ->
                if (!isNavigated && pages.any { it.pageNumber == targetStartPage }) {
                    isNavigated = true
                    _showProgressDialog.value = false
                    _readyToNavigateBookId.value = bookId
                }
            }
        }

        viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(workId).collect { workInfo ->
                if (workInfo != null && workInfo.state == WorkInfo.State.FAILED) {
                    _showProgressDialog.value = false
                    val errorMsg = workInfo.outputData.getString("error") ?: "حدث خطأ غير معروف في خوادم الذكاء الاصطناعي."
                    _errorMessage.value = "توقفت المعالجة: $errorMsg"
                }
            }
        }
     }
}