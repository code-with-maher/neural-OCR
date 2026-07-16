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
import com.a.labs.data.local.SettingsManager
import com.a.labs.data.local.room.entity.BookEntity
import com.a.labs.data.repository.BookRepository
import com.a.labs.domain.usecase.PdfChunkerUseCase
import com.a.labs.worker.PdfExtractionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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

    private val _activeBottomSheet = MutableStateFlow<String?>(null)
    val activeBottomSheet: StateFlow<String?> = _activeBottomSheet.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _readyToNavigateBookId = MutableStateFlow<String?>(null)
    val readyToNavigateBookId: StateFlow<String?> = _readyToNavigateBookId.asStateFlow()

    var pendingFileUri: Uri? = null
    var pendingTotalPages: Int = 0
    var pendingBookId: String = ""
    var pendingFileName: String = ""

    private var targetFailedBookId: String? = null

    fun dismissBottomSheet() { _activeBottomSheet.value = null }
    fun onNavigated() { _readyToNavigateBookId.value = null }

    private fun getFileName(context: Context, uri: Uri): String {
        var name = ""
        if (uri.scheme == "content") {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val index = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (index != -1) {
                        name = it.getString(index)
                    }
                }
            }
        }
        if (name.isBlank()) {
            name = uri.path?.let { File(it).name } ?: ""
        }
        name = if (name.contains(".")) name.substringBeforeLast(".") else name
        return name.ifBlank { "كتاب جديد" }
    }

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
                pendingFileName = getFileName(context, safeUri)
                _activeBottomSheet.value = "RANGE_PICKER"

            } catch (e: Exception) {
                _errorMessage.value = "خطأ أثناء التهيئة: ${e.localizedMessage}"
            }
        }
    }

    fun onBookClicked(context: Context, bookId: String) {
        viewModelScope.launch {
            val chunks = repository.getChunksForBook(bookId)
            val hasFailed = chunks.any { it.status == "FAILED" }
            val isPending = chunks.any { it.status == "PENDING" || it.status == "PROCESSING" }

            if (hasFailed || isPending) {
                val settings = SettingsManager(context)
                val apiKey = settings.geminiKey.first()
                targetFailedBookId = bookId

                if (apiKey.isBlank())  {
                    _activeBottomSheet.value = "MISSING_KEY"
                } else {
                    _activeBottomSheet.value = "RETRY_PROCESSING"
                }
            } else {
                _readyToNavigateBookId.value = bookId
            }
        }
    }

    fun retryFailedBook(context: Context) {
        _activeBottomSheet.value = null
        targetFailedBookId?.let { bookId ->
            enqueueWorker(context, bookId, 1, 1)
        }
    }

    fun startExtraction(context: Context, title: String, startPage: Int, endPage: Int) {
        _activeBottomSheet.value = null

        viewModelScope.launch {
            try {
                val selectedTotalPages = (endPage - startPage + 1)
                val newBook = BookEntity(
                    id = pendingBookId,
                    title = title.ifBlank { pendingFileName },
                    sourcePdfUri = pendingFileUri.toString(),
                    totalPages = selectedTotalPages,
                    lastReadPage = 1
                )
                repository.insertBook(newBook)
                enqueueWorker(context, pendingBookId, startPage, endPage)
            } catch (e: Exception) {
                _errorMessage.value = "فشل إنشاء الكتاب: ${e.localizedMessage}"
            }
        }
    }

    private fun enqueueWorker(context: Context, bookId: String, startPage: Int, endPage: Int) {
        val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
        val workRequest = OneTimeWorkRequestBuilder<PdfExtractionWorker>()
            .setConstraints(constraints)
            .setInputData(workDataOf("bookId" to bookId, "startPage" to startPage, "endPage" to endPage))
            .build()

        WorkManager.getInstance(context).enqueue(workRequest)
        _activeBottomSheet.value = "PROGRESS"

        viewModelScope.launch {
            var isNavigated = false
            repository.getPagesForBook(bookId).collect { pages ->
                if (!isNavigated && pages.isNotEmpty()) {
                    isNavigated = true
                    _activeBottomSheet.value = null
                    _readyToNavigateBookId.value = bookId
                }
            }
        }

        viewModelScope.launch {
            WorkManager.getInstance(context).getWorkInfoByIdFlow(workRequest.id).collect { workInfo ->
                if (workInfo != null && workInfo.state == WorkInfo.State.FAILED) {
                    _activeBottomSheet.value = null
                    val errorMsg = workInfo.outputData.getString("error") ?: "حدث خطأ."
                    _errorMessage.value = "توقفت المعالجة: $errorMsg"
                }
            }
        }
    }

    fun clearError() { _errorMessage.value = null  }
}
