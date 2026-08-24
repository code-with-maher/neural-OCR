package com.a.labs.ui.reader

import android.widget.Toast
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import com.a.labs.data.audio.AudioState
import com.a.labs.ui.reader.components.ReaderBottomBar
import com.a.labs.ui.reader.components.ReaderBottomSheets
import com.a.labs.ui.reader.components.ReaderContent
import com.a.labs.ui.reader.components.ReaderTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    navController: NavHostController,
    viewModel: ReaderViewModel,
    bookId: String
) {
    val context = LocalContext.current
    val book by viewModel.currentBook.collectAsState()
    val pageData by viewModel.currentPageData.collectAsState()
    val currentPageNumber by viewModel.currentPageNumber.collectAsState()
    val isProcessing by viewModel.isProcessing.collectAsState()
    val isFailed by viewModel.isFailed.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()

    val audioState by viewModel.audioController.audioState.collectAsState()
    val highlightedIndex by viewModel.audioController.highlightedParagraphIndex.collectAsState()
    val audioError by viewModel.audioController.errorMessage.collectAsState()

    var activeBottomSheet by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(audioError) {
        if (audioError != null) {
            activeBottomSheet = "AUDIO_ERROR"
        }
    }

    ReaderBottomSheets(
        activeSheet = activeBottomSheet,
        sheetState = sheetState,
        audioError = audioError,
        onDismiss = {
            if (activeBottomSheet == "AUDIO_ERROR") {
                viewModel.audioController.clearError()
            }
            activeBottomSheet = null
        },
        onConfirmDelete = {
            activeBottomSheet = null
            viewModel.deleteCurrentBook { navController.popBackStack() }
        },
        onClearAudioError = { viewModel.audioController.clearError() },
        onRestartBook = {
            activeBottomSheet = null
            book?.let { viewModel.loadPage(it.id, 1) }
        },
        onNavigateLibrary = {
            activeBottomSheet = null
            navController.popBackStack()
        },
        onStopProcessing = {
            activeBottomSheet = null
            viewModel.playAudio()
        }
    )

    Scaffold(
        topBar = {
            ReaderTopBar(
                book = book,
                pageData = pageData,
                onBackClick = { navController.popBackStack() },
                onNavigateSettings = { navController.navigate("settings") },
                onExportAudio = { viewModel.exportCurrentAudio() },
                onDeleteBookClick = { activeBottomSheet = "DELETE_CONFIRM" }
            )
        },
        bottomBar = {
            ReaderBottomBar(
                audioState = audioState,
                currentPageNumber = currentPageNumber,
                totalPages = book?.totalPages,
                onPrevPage = { viewModel.prevPage() },
                onNextPage = { viewModel.nextPage { activeBottomSheet = "BOOK_ENDED" } },
                onSeekBackward = { viewModel.audioController.seekBackward() },
                onSeekForward = { viewModel.audioController.seekForward() },
                onPlayButtonClick = {
                    if (audioState == AudioState.PROCESSING) {
                        activeBottomSheet = "PROCESSING_ALERT"
                    } else {
                        viewModel.playAudio()
                    }
                }
            )
        }
    ) { padding ->
        ReaderContent(
            modifier = Modifier.padding(padding),
            pageData = pageData,
            isProcessing = isProcessing,
            isFailed = isFailed,
            highlightedIndex = highlightedIndex,
            onNavigateBack = { navController.popBackStack() }
        )
    }
}