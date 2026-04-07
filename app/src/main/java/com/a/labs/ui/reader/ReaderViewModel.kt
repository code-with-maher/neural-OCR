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

    fun loadBook(bookId: String) {
        viewModelScope.launch {
            val book = repository.getBookById(bookId)
            _currentBook.value = book
            book?.let {
                _currentPageNumber.value = it.lastReadPage
                loadPage(bookId, it.lastReadPage)
            }
        }
    }

    fun loadPage(bookId: String, pageNumber: Int) {
        viewModelScope.launch {
            val page = repository.getPageByNumber(bookId, pageNumber)
            _currentPageData.value = page
            _currentPageNumber.value = pageNumber
            repository.updateLastReadPage(bookId, pageNumber)
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
        audioController.playPage(book.id, _currentPageNumber.value)
    }

    override fun onCleared() {
        super.onCleared()
        audioController.release()
    }
}