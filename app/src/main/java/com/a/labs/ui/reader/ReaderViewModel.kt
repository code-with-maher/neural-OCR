package com.a.labs.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.a.labs.data.audio.AudioExporter
import com.a.labs.data.audio.AudioPlayerController
import com.a.labs.data.local.SettingsManager
import com.a.labs.data.local.room.entity.BookEntity
import com.a.labs.data.local.room.entity.PageEntity
import com.a.labs.data.repository.BookRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File

class ReaderViewModel(
    private val repository: BookRepository,
    val audioController: AudioPlayerController,
    private val settingsManager: SettingsManager,
    private val audioExporter: AudioExporter
) : ViewModel() {

    private val _currentBook = MutableStateFlow<BookEntity?>(null)
    val currentBook: StateFlow<BookEntity?> = _currentBook.asStateFlow()

    private val _currentPageNumber = MutableStateFlow(1)
    val currentPageNumber: StateFlow<Int> = _currentPageNumber.asStateFlow()

    private val _currentPageData = MutableStateFlow<PageEntity?>(null)
    val currentPageData: StateFlow<PageEntity?> = _currentPageData.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _isFailed = MutableStateFlow(false)
    val isFailed: StateFlow<Boolean> = _isFailed.asStateFlow()

    private val _toastMessage = MutableStateFlow<String?>(null)
    val toastMessage: StateFlow<String?> = _toastMessage.asStateFlow()

    private var pageCollectorJob: Job? = null

    init {
        audioController.connect()
    }

    fun clearToast() {
        _toastMessage.value = null
    }

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            try {
                val book = repository.getBookById(bookId)
                _currentBook.value = book
                book?.let { b ->
                    loadPage(b.id, b.lastReadPage)
                }
            } catch (_: Exception) {
                _toastMessage.value = "خطأ في تحميل الكتاب"
            }
        }
    }

    fun loadPage(bookId: String, pageNumber: Int) {
        pageCollectorJob?.cancel()
        _currentPageNumber.value = pageNumber

        pageCollectorJob = viewModelScope.launch {
            repository.updateLastReadPage(bookId, pageNumber)

            repository.getPagesForBook(bookId).collect { pages ->
                val page = pages.find { it.pageNumber == pageNumber }
                _currentPageData.value = page

                val chunks = repository.getChunksForBook(bookId)
                _isFailed.value = chunks.any { it.status == "FAILED" }
                _isProcessing.value = chunks.any { it.status == "PENDING" || it.status == "PROCESSING" }
            }
        }
    }

    fun nextPage(onBookEnded: () -> Unit) {
        val book = _currentBook.value ?: return
        if (_currentPageNumber.value < book.totalPages) {
            loadPage(book.id, _currentPageNumber.value + 1)
        } else {
            onBookEnded()
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

    fun exportCurrentAudio() {
        viewModelScope.launch {
            val engine = settingsManager.ttsEngine.first()
            if (engine == "SYSTEM") {
                _toastMessage.value = "لا يمكن تحميل الصوت مع محرك النظام (النطق المباشر)."
                return@launch
            }

            val audioPath = _currentPageData.value?.audioUri
            if (audioPath == null) {
                _toastMessage.value = "يجب تشغيل الصوت أولاً لتوليده قبل تحميله."
                return@launch
            }

            val file = File(audioPath)
            val bookTitle = _currentBook.value?.title ?: "book"
            val pageNum = _currentPageNumber.value

            val result = audioExporter.exportAudio(
                sourceFile = file,
                bookTitle = bookTitle,
                pageNumber = pageNum
            )

            if (result.isSuccess) {
                _toastMessage.value = "تم حفظ الملف الصوتي في التنزيلات."
            } else {
                _toastMessage.value = "فشل في حفظ الملف الصوتي: ${result.exceptionOrNull()?.message}"
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        pageCollectorJob?.cancel()
        audioController.disconnect()
    }
}