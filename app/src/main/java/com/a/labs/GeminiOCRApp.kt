package com.a.labs

import android.app.Application
import com.a.labs.core.crash.GlobalCrashHandler
import com.a.labs.data.audio.AudioExporter
import com.a.labs.data.audio.AudioPlayerController
import com.a.labs.data.local.SettingsManager
import com.a.labs.data.local.room.AppDatabase
import com.a.labs.data.repository.BookRepository
import com.a.labs.domain.usecase.PdfChunkerUseCase

class GeminiOCRApp : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: BookRepository
        private set

    lateinit var settingsManager: SettingsManager
        private set

    lateinit var chunkerUseCase: PdfChunkerUseCase
        private set

    lateinit var audioPlayerController: AudioPlayerController
        private set

    lateinit var audioExporter: AudioExporter
        private set

    override fun onCreate() {
        super.onCreate()

        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler(GlobalCrashHandler(this, defaultHandler))

        database = AppDatabase.getDatabase(this)
        repository = BookRepository(database.bookDao())
        settingsManager = SettingsManager(this)
        chunkerUseCase = PdfChunkerUseCase(this)
        audioPlayerController = AudioPlayerController(this, repository, settingsManager)
        audioExporter = AudioExporter(this)
    }
}