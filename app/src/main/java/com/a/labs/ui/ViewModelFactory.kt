package com.a.labs.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.a.labs.data.audio.AudioPlayerController
import com.a.labs.data.repository.BookRepository
import com.a.labs.domain.usecase.PdfChunkerUseCase
import com.a.labs.ui.library.LibraryViewModel
import com.a.labs.ui.reader.ReaderViewModel

class ViewModelFactory(
    private val repository: BookRepository,
    private val chunkerUseCase: PdfChunkerUseCase? = null,
    private val audioController: AudioPlayerController? = null
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return when {
            modelClass.isAssignableFrom(LibraryViewModel::class.java) -> 
                LibraryViewModel(repository, chunkerUseCase!!) as T
            modelClass.isAssignableFrom(ReaderViewModel::class.java) -> 
                ReaderViewModel(repository, audioController!!) as T
            else -> throw IllegalArgumentException("Unknown ViewModel class")
        }
    }
}