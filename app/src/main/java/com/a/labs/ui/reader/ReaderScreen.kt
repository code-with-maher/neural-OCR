package com.a.labs.ui.reader

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    navController: NavHostController,
    viewModel: ReaderViewModel,
    bookId: String
) {
    val book by viewModel.currentBook.collectAsState()
    val pageData by viewModel.currentPageData.collectAsState()
    val currentPageNumber by viewModel.currentPageNumber.collectAsState()
    val isPlaying by viewModel.audioController.isPlaying.collectAsState()
    
    var showSaveDialog by remember { mutableStateOf(false) }
    var tempTitle by remember { mutableStateOf("") }

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        book?.title ?: "جاري التحميل...", 
                        maxLines = 1, 
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold 
                    ) 
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    if (book?.title?.startsWith("كتاب جديد") == true) {
                        IconButton(onClick = { 
                            tempTitle = book?.title ?: ""
                            showSaveDialog = true 
                        }) {
                            Icon(Icons.Default.Save, contentDescription = "حفظ الكتاب باسم")
                        }
                    }
                    IconButton(onClick = { /* More options logic */ }) {
                        Icon(Icons.Default.MoreVert, contentDescription = "المزيد")
                    }
                }
            )
        },
        bottomBar = {
            Surface(
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.prevPage() }) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "الصفحة السابقة")
                        }
                        IconButton(onClick = { viewModel.audioController.seekBackward() }) {
                            Icon(Icons.Default.Replay10, contentDescription = "تراجع 10 ثواني")
                        }
                        LargeFloatingActionButton(
                            onClick = { viewModel.playAudio() },
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "إيقاف مؤقت" else "تشغيل"
                             )
                        }
                        IconButton(onClick = { viewModel.audioController.seekForward() }) {
                            Icon(Icons.Default.Forward10, contentDescription = "تقديم 10 ثواني")
                        }
                        IconButton(onClick = { viewModel.nextPage() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "الصفحة التالية")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "صفحة $currentPageNumber من ${book?.totalPages ?: "?"}",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
        }
    ) { padding ->
        if (pageData == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("جاري معالجة الصفحة بالذكاء الاصطناعي...")
                }
            }
        } else {
            val paragraphs = pageData!!.markdownContent.split("\n\n").filter { it.isNotBlank() }
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(paragraphs) { paragraph ->
                    Text(
                        text = paragraph,
                        style = MaterialTheme.typography.bodyLarge,
                        lineHeight = 32.sp,
                        textAlign = TextAlign.Justify
                    )
                }
            }
        }
    }

    if (showSaveDialog) {
        AlertDialog(
            onDismissRequest = { showSaveDialog = false },
            title = { Text("حفظ الكتاب") },
            text = {
                OutlinedTextField(
                    value = tempTitle,
                    onValueChange = { tempTitle = it },
                    label = { Text("اسم الكتاب") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    /* Logic to update title in DB via ViewModel */
                    showSaveDialog = false
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showSaveDialog = false }) { Text("إلغاء") }
            }
        )
     }
}