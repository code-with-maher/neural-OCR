package com.a.labs.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
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
import androidx.compose.ui.platform.LocalContext
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
    val context = LocalContext.current
    val book by viewModel.currentBook.collectAsState()
    val pageData by viewModel.currentPageData.collectAsState()
    val isChunkFailed by viewModel.isChunkFailed.collectAsState()
    val currentPageNumber by viewModel.currentPageNumber.collectAsState()
    val isPlaying by viewModel.audioController.isPlaying.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val toastMessage by viewModel.toastMessage.collectAsState()

    var showMenu by remember { mutableStateOf(false) }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    LaunchedEffect(bookId) {
        viewModel.loadBook(bookId)
    }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("تنبيه", fontWeight = FontWeight.Bold) },
            text = { Text(errorMessage!!) },
            confirmButton = {
                Button(onClick = { viewModel.clearError() }) { Text("حسناً") }
            }
        )
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("تأكيد الحذف") },
            text = { Text("هل أنت متأكد أنك تريد حذف هذا الكتاب وكل محتوياته؟") },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    onClick = {
                        showDeleteConfirmDialog = false
                        viewModel.deleteCurrentBook {
                            navController.popBackStack()
                        }
                    }
                ) { Text("حذف") }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) { Text("إلغاء") }
            }
        )
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
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "المزيد من الخيارات")
                        }
                         DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("نسخ النص") },
                                onClick = {
                                    showMenu = false
                                    pageData?.markdownContent?.let { content ->
                                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                        val clip = ClipData.newPlainText("Copied Text", content)
                                        clipboard.setPrimaryClip(clip)
                                        Toast.makeText(context, "تم نسخ النص", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("الإعدادات") },
                                onClick = {
                                    showMenu = false
                                    navController.navigate("settings")
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("تحميل الصوت") },
                                onClick = {
                                    showMenu = false
                                    viewModel.exportCurrentAudio(context)
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("حذف الكتاب", color = MaterialTheme.colorScheme.error) },
                                onClick = {
                                    showMenu = false
                                    showDeleteConfirmDialog = true
                                }
                            )
                        }
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
                if (isChunkFailed) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, contentDescription = "خطأ", modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.height(16.dp))
                        Text("فشلت معالجة هذه الصفحة (تأكد من الإنترنت).", textAlign = TextAlign.Center)
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { viewModel.retryProcessing(context) }) {
                            Text("إعادة المحاولة")
                        }
                    }
                } else {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator()
                        Spacer(Modifier.height(16.dp))
                        Text("جاري معالجة الصفحة أو انتظار الوصول...")
                    }
                }
            }
        } else {
            val content = pageData!!.markdownContent
            if (content == "الصفحة فارغة") {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(
                        text = "هذه الصفحة فارغة أو تحتوي على صورة فقط بدون نص.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.outline,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                val paragraphs = content.split("\n\n").filter { it.isNotBlank() }
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
     }
}