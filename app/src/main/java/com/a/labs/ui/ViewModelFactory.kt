package com.a.labs.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.a.labs.data.audio.AudioExporter
import com.a.labs.data.audio.AudioPlayerController
import com.a.labs.data.local.SettingsManager
import com.a.labs.data.repository.BookRepository
import com.a.labs.domain.usecase.PdfChunkerUseCase
import com.a.labs.ui.library.LibraryViewModel
import com.a.labs.ui.reader.ReaderViewModel

class LibraryViewModelFactory(
    private val repository: BookRepository,
    private val chunkerUseCase: PdfChunkerUseCase
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(LibraryViewModel::class.java)) {
            return LibraryViewModel(repository, chunkerUseCase) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}

class ReaderViewModelFactory(
    private val repository: BookRepository,
    private val audioController: AudioPlayerController,
    private val settingsManager: SettingsManager,
    private val audioExporter: AudioExporter
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            return ReaderViewModel(
                repository = repository,
                audioController = audioController,
                settingsManager = settingsManager,
                audioExporter = audioExporter
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}