package com.a.labs.ui.reader

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.a.labs.core.AppLogger
import com.a.labs.data.audio.AudioPlayerController
import com.a.labs.data.local.room.entity.BookEntity
import com.a.labs.data.local.room.entity.PageEntity
import com.a.labs.data.repository.BookRepository
import com.a.labs.worker.PdfExtractionWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileInputStream

class ReaderViewModel(
    private val repository: BookRepository,
    val audioController: AudioPlayerController
) : ViewModel() {

    private val _currentBook = MutableStateFlow<BookEntity?>(null)
    val currentBook: StateFlow<BookEntity?> = _currentBook.asStateFlow()

    private val _currentPageNumber = MutableStateFlow(1)
    val currentPageNumber: StateFlow<Int> = _currentPageNumber.asStateFlow()

    private val _currentPageData = MutableStateFlow<PageEntity?>(null)
    val currentPageData: StateFlow<PageEntity?> = _currentPageData.asStateFlow()

    private val _isChunkFailed = MutableStateFlow(false)
    val isChunkFailed: StateFlow<Boolean> = _isChunkFailed.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private var statusMonitorJob: Job? = null

    fun clearError() { _errorMessage.value = null }
    fun clearToast() { _toastMessage.value = null }

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            try {
                val book = repository.getBookById(bookId)
                _currentBook.value = book
                book?.let { b ->
                    _currentPageNumber.value = b.lastReadPage
                    startStatusMonitoring(b.id)
                    
                    viewModelScope.launch {
                        repository.getPagesForBook(b.id).collect { pages ->
                            val page = pages.find { it.pageNumber == _currentPageNumber.value }
                            _currentPageData.value = page
                            if (page != null) _isChunkFailed.value = false
                        }
                    }
                } ?: run {
                    _errorMessage.value = "الكتاب غير موجود في السجلات."
                }
            } catch (e: Exception) {
                _errorMessage.value = "خطأ في تحميل الكتاب: ${e.localizedMessage}"
            }
        }
    }

    private fun startStatusMonitoring(bookId: String) {
        statusMonitorJob?.cancel()
        statusMonitorJob = viewModelScope.launch(Dispatchers.IO) {
            while (true) {
                if (_currentPageData.value == null) {
                    val chunks = repository.getChunksForBook(bookId)
                    val chunk = chunks.find { 
                        (_currentPageNumber.value - 1) >= it.startPage && (_currentPageNumber.value - 1) < it.endPage 
                    }
                    _isChunkFailed.value = chunk?.status == "FAILED"
                }
                delay(2000) 
            }
        }
    }

    fun retryProcessing(context: Context) {
        val bookId = _currentBook.value?.id ?: return
        _isChunkFailed.value = false
        val workRequest = OneTimeWorkRequestBuilder<PdfExtractionWorker>()
             .setInputData(workDataOf("bookId" to bookId))
            .build()
        WorkManager.getInstance(context).enqueue(workRequest)
    }

    fun loadPage(bookId: String, pageNumber: Int) {
        viewModelScope.launch {
            _currentPageNumber.value = pageNumber
            repository.updateLastReadPage(bookId, pageNumber)
            val pages = repository.getPagesForBook(bookId).first()
            val page = pages.find { it.pageNumber == pageNumber }
            _currentPageData.value = page
            if (page != null) _isChunkFailed.value = false
        }
    }

    fun nextPage() {
        val book = _currentBook.value ?: return
        if (_currentPageNumber.value < book.totalPages) {
            loadPage(book.id, _currentPageNumber.value + 1)
        }
    }

    fun prevPage() {
        val book = _currentBook.value ?: return
        if (_currentPageNumber.value > 1) {
            loadPage(book.id, _currentPageNumber.value - 1)
        }
    }

    fun playAudio() {
        val book = _currentBook.value ?: return
        if (_currentPageData.value == null) {
            _toastMessage.value = "النص غير جاهز بعد."
            return
        }
        audioController.playPage(book.id, _currentPageNumber.value)
    }

    fun deleteCurrentBook(onDeleted: () -> Unit) {
        viewModelScope.launch {
            _currentBook.value?.let { book ->
                repository.deleteBook(book.id)
                onDeleted()
            }
        }
    }

    fun exportCurrentAudio(context: Context) {
        val audioPath = _currentPageData.value?.audioUri
        if (audioPath == null || !File(audioPath).exists()) {
            _toastMessage.value = "يجب تشغيل الصوت أولاً لتوليده قبل تحميله."
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val file = File(audioPath)
                val bookTitle = _currentBook.value?.title?.replace(" ", "_") ?: "book"
                val pageNum = _currentPageNumber.value
                val fileName = "${bookTitle}_page_$pageNum.wav"

                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                        put(MediaStore.Downloads.MIME_TYPE, "audio/wav")
                        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    if (uri != null) {
                        resolver.openOutputStream(uri)?.use { outputStream ->
                            FileInputStream(file).use { inputStream -> inputStream.copyTo(outputStream) }
                        }
                        _toastMessage.value = "تم حفظ الملف الصوتي في التنزيلات."
                    } else {
                        _toastMessage.value = "فشل في حفظ الملف الصوتي."
                    }
                } else {
                    @Suppress("DEPRECATION")
                    val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                    val destFile = File(downloadsDir, fileName)
                    FileInputStream(file).use { input ->
                        java.io.FileOutputStream(destFile).use { output -> input.copyTo(output) }
                    }
                    _toastMessage.value = "تم حفظ الملف الصوتي في التنزيلات."
                }
            } catch (e: Exception) {
                _toastMessage.value = "حدث خطأ أثناء الحفظ: ${e.localizedMessage}"
                viewModelScope.launch { 
                    AppLogger.log(context, true, "خطأ تصدير الصوت: \n${e.stackTraceToString()}") 
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
         statusMonitorJob?.cancel()
        audioController.release()
     }
}