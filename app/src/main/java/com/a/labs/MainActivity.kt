package com.a.labs

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.navigation.compose.rememberNavController
import com.a.labs.data.audio.AudioPlayerController
import com.a.labs.data.local.SettingsManager
import com.a.labs.data.local.room.AppDatabase
import com.a.labs.data.repository.BookRepository
import com.a.labs.domain.usecase.PdfChunkerUseCase
import com.a.labs.ui.ViewModelFactory
import com.a.labs.ui.navigation.NavGraph
import com.a.labs.ui.theme.ALabsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val database = AppDatabase.getDatabase(this)
        val repository = BookRepository(database.bookDao())
        val settingsManager = SettingsManager(this)
        val chunkerUseCase = PdfChunkerUseCase(this)
        val audioController = AudioPlayerController(this, repository, settingsManager)

        val libraryFactory = ViewModelFactory(repository, chunkerUseCase = chunkerUseCase)
        val readerFactory = ViewModelFactory(repository, audioController = audioController)

        setContent {
            ALabsTheme {
                val navController = rememberNavController()
                
                val permissionLauncher = rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission()
                ) { _ -> }

                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }

                NavGraph(
                    navController = navController,
                    factory = libraryFactory,
                    readerFactory = readerFactory
                )
            }
        }
    }
}