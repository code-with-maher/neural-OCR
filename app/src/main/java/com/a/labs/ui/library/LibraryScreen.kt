package com.a.labs.ui.library

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    navController: NavHostController,
    viewModel: LibraryViewModel
) {
    val context = LocalContext.current
    val books by viewModel.books.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    val activeBottomSheet by viewModel.activeBottomSheet.collectAsState()
    val readyToNavigateBookId by viewModel.readyToNavigateBookId.collectAsState()

    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    val pdfPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { viewModel.prepareBook(context, it) } }

    LaunchedEffect(readyToNavigateBookId) {
        readyToNavigateBookId?.let { bookId ->
            navController.navigate("reader/$bookId")
            viewModel.onNavigated()
        }
    }

    if (errorMessage != null) {
        AlertDialog(
            onDismissRequest = { viewModel.clearError() },
            title = { Text("تنبيه", fontWeight = FontWeight.Bold) },
            text = { Text(errorMessage!!) },
            confirmButton = { Button(onClick = { viewModel.clearError() }) { Text("حسناً") } }
        )
    }

    if (activeBottomSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { 
                if (activeBottomSheet != "PROGRESS") viewModel.dismissBottomSheet() 
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (activeBottomSheet) {
                    "RANGE_PICKER" -> {
                        var startPageStr by remember { mutableStateOf("1") }
                        var endPageStr by remember { mutableStateOf(viewModel.pendingTotalPages.toString()) }
                        var title by remember { mutableStateOf("") }

                        Text("إعدادات المعالجة", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("يحتوي الملف على ${viewModel.pendingTotalPages} صفحة.")
                        OutlinedTextField(
                            value = title, onValueChange = { title = it },
                            label = { Text("اسم الكتاب (اختياري)") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = startPageStr, onValueChange = { startPageStr = it },
                                label = { Text("من صفحة") }, keyboardOptions =  KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                            OutlinedTextField(
                                value = endPageStr, onValueChange = { endPageStr = it },
                                label = { Text("إلى صفحة") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.weight(1f)
                            )
                        }
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.dismissBottomSheet() }) { Text("إلغاء") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                val start = startPageStr.toIntOrNull() ?: 1
                                val end = endPageStr.toIntOrNull() ?: viewModel.pendingTotalPages
                                viewModel.startExtraction(context, title, start, end)
                            }) { Text("بدء المعالجة") }
                        }
                    }
                    
                    "MISSING_KEY" -> {
                        Icon(Icons.Default.Settings, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                        Text("مفتاح Gemini مفقود", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("لا يمكن متابعة معالجة هذا الكتاب لعدم توفر مفتاح الذكاء الاصطناعي. يرجى إضافته من الإعدادات.", textAlign = TextAlign.Center)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.dismissBottomSheet() }) { Text("إلغاء") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { 
                                viewModel.dismissBottomSheet()
                                navController.navigate("settings") 
                            }) { Text("الذهاب للإعدادات") }
                        }
                    }

                    "RETRY_PROCESSING" -> {
                        Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("كتاب غير مكتمل", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("توقفت معالجة هذا الكتاب سابقاً أو لا تزال قيد الانتظار. هل تريد استئناف المعالجة الآن؟", textAlign = TextAlign.Center)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { viewModel.dismissBottomSheet() }) { Text("إلغاء") }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = { viewModel.retryFailedBook(context) }) { Text("استئناف المعالجة") }
                        }
                    }

                    "PROGRESS" -> {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Text("جاري الاتصال بخوادم الذكاء الاصطناعي...\nيرجى الانتظار، يعتمد الوقت على سرعة الإنترنت.", textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = { Text("مكتبتي الصوتية", fontWeight = FontWeight.Black) },
                actions = {
                    IconButton(onClick = { navController.navigate("settings") }) {
                        Icon(Icons.Default.Settings, contentDescription = "الإعدادات")
                    }
                }
            )
        },
        floatingActionButton = {
            LargeFloatingActionButton(
                onClick = {  pdfPickerLauncher.launch("application/pdf") },
                containerColor = MaterialTheme.colorScheme.primary
            ) { Icon(Icons.Default.Add, contentDescription = "إضافة كتاب PDF جديد", modifier = Modifier.size(36.dp)) }
        }
    ) { padding ->
        if (books.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("😔", fontSize = 64.sp)
                    Spacer(Modifier.height(16.dp))
                    Text("قائمة الكتب فارغة", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.outline)
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(books, key = { it.id }) { book ->
                    Card(
                        onClick = { viewModel.onBookClicked(context, book.id) },
                        modifier = Modifier.fillMaxWidth().clearAndSetSemantics {
                            contentDescription = "كتاب ${book.title}، عدد الصفحات ${book.totalPages}. انقر لفتح أو متابعة المعالجة."
                        },
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        ListItem(
                            headlineContent = { Text(book.title, fontWeight = FontWeight.Bold) },
                            supportingContent = { Text("عدد الصفحات: ${book.totalPages}") },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, null) }
                        )
                    }
                }
            }
        }
      }
}