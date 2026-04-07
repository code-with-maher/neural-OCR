package com.a.labs.ui.reader

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.a.labs.data.audio.AudioPlayerController
import com.a.labs.data.local.room.entity.BookEntity
import com.a.labs.data.local.room.entity.PageEntity
import com.a.labs.data.repository.BookRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

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

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    fun clearError() { _errorMessage.value = null }

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            try {
                val book = repository.getBookById(bookId)
                _currentBook.value = book
                book?.let { b ->
                    _currentPageNumber.value = b.lastReadPage
                    
                    // الحل العبقري: مراقبة قاعدة البيانات باستمرار، بمجرد أن ينهي جيميناي الحفظ ستتحدث الواجهة فوراً!
                    viewModelScope.launch {
                        repository.getPagesForBook(b.id).collect { pages ->
                            _currentPageData.value = pages.find { it.pageNumber == _currentPageNumber.value }
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

    fun loadPage(bookId: String, pageNumber: Int) {
        viewModelScope.launch {
            _currentPageNumber.value = pageNumber
            repository.updateLastReadPage(bookId, pageNumber)
            val pages = repository.getPagesForBook(bookId).first()
            _currentPageData.value = pages.find { it.pageNumber == pageNumber }
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
            _errorMessage.value = "النص غير جاهز بعد. يرجى انتظار معالجة الذكاء الاصطناعي."
            return
        }
        audioController.playPage(book.id, _currentPageNumber.value)
    }

    override fun onCleared() {
        super.onCleared()
        audioController.release()
    }
}