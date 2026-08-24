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
import com.a.labs.ui.LibraryViewModelFactory
import com.a.labs.ui.ReaderViewModelFactory
import com.a.labs.ui.navigation.NavGraph
import com.a.labs.ui.theme.ALabsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val app = application as GeminiOCRApp

        val libraryFactory = LibraryViewModelFactory(
            repository = app.repository,
            chunkerUseCase = app.chunkerUseCase
        )

        val readerFactory = ReaderViewModelFactory(
            repository = app.repository,
            audioController = app.audioPlayerController,
            settingsManager = app.settingsManager,
            audioExporter = app.audioExporter
        )

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
                    libraryFactory = libraryFactory,
                    readerFactory = readerFactory
                )
            }
        }
    }
}