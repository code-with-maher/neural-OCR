package com.a.labs.ui.reader

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.a.labs.data.audio.AudioState

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

    var showMenu by remember { mutableStateOf(false) }
    var activeBottomSheet by remember { mutableStateOf<String?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    LaunchedEffect(bookId) { viewModel.loadBook(bookId) }

    LaunchedEffect(toastMessage) {
        toastMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            viewModel.clearToast()
        }
    }

    LaunchedEffect(audioError) {
        if (audioError != null) activeBottomSheet = "AUDIO_ERROR"
    }

    if (activeBottomSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { 
                if (activeBottomSheet == "AUDIO_ERROR") viewModel.audioController.clearError()
                activeBottomSheet = null 
            },
            sheetState = sheetState
        ) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                when (activeBottomSheet) {
                    "DELETE_CONFIRM" -> {
                        Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                        Text("تأكيد الحذف", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("هل أنت متأكد أنك تريد حذف هذا الكتاب وكل محتوياته؟", textAlign = TextAlign.Center)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { activeBottomSheet = null }) { Text("إلغاء") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                onClick = {
                                    activeBottomSheet = null
                                    viewModel.deleteCurrentBook { navController.popBackStack() }
                                 }
                            ) { Text("حذف") }
                        }
                    }
                    "AUDIO_ERROR" -> {
                        Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.error)
                        Text("خطأ في الصوت", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text(audioError ?: "حدث خطأ غير معروف.", textAlign = TextAlign.Center)
                        Button(onClick = {
                            viewModel.audioController.clearError()
                            activeBottomSheet = null
                        }) { Text("حسناً") }
                    }
                    "BOOK_ENDED" -> {
                        Icon(Icons.Default.CheckCircle, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("لقد انتهى الكتاب!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("تهانينا! لقد أتممت قراءة واستماع هذا الكتاب بنجاح.", textAlign = TextAlign.Center)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { activeBottomSheet = null }) { Text("إغلاق") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    activeBottomSheet = null
                                    viewModel.loadPage(book!!.id, 1)
                                }
                            ) { Text("إعادة القراءة") }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    activeBottomSheet = null
                                    navController.popBackStack()
                                }
                            ) { Text("المكتبة") }
                        }
                    }
                    "PROCESSING_ALERT" -> {
                        Icon(Icons.Default.Memory, null, modifier = Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                        Text("الذكاء الاصطناعي في خضم العمل!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                        Text("نقوم حالياً بتهيئة وهندسة النصوص صوتياً في الخلفية لضمان تجربة استماع مثالية. هل تود إيقاف هذه العملية؟", textAlign = TextAlign.Center)
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { activeBottomSheet = null }) { 
                                Text("لا، دعه يستمر بالإبداع") 
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                onClick = {
                                    activeBottomSheet = null
                                    viewModel.playAudio()
                                }
                            ) { 
                                Text("إيقاف المعالجة") 
                            }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(book?.title ?: "جاري التحميل...", maxLines = 1, fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "خيارات إضافية")
                        }
                        DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                            DropdownMenuItem(text = { Text("نسخ النص") }, onClick = {
                                showMenu = false
                                pageData?.markdownContent?.let {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    clipboard.setPrimaryClip(ClipData.newPlainText("Text", it))
                                    Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
                                }
                            })
                            DropdownMenuItem(text = { Text("الإعدادات") }, onClick = {
                                showMenu = false
                                navController.navigate("settings")
                            })
                            DropdownMenuItem(text = { Text("تحميل الصوت") }, onClick = {
                                showMenu = false
                                viewModel.exportCurrentAudio(context)
                            })
                            DropdownMenuItem(text = { Text("حذف الكتاب", color = MaterialTheme.colorScheme.error) }, onClick = {
                                showMenu = false
                                activeBottomSheet = "DELETE_CONFIRM"
                            })
                        }
                    }
                }
            )
        },
        bottomBar = {
            Surface(tonalElevation = 8.dp, shadowElevation = 8.dp) {
                Column(modifier = Modifier.fillMaxWidth().padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.prevPage() }) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "الصفحة السابقة")
                        }
                        IconButton(onClick = { viewModel.audioController.seekBackward() }) {
                            Icon(Icons.Default.Replay10, contentDescription = "تراجع 10 ثواني")
                        }

                        val btnText = when (audioState) {
                            AudioState.PROCESSING -> "جاري المعالجة"
                            AudioState.PLAYING -> "إيقاف"
                            else -> "تشغيل"
                        }

                        ExtendedFloatingActionButton(
                            onClick = { 
                                if (audioState == AudioState.PROCESSING) {
                                    activeBottomSheet = "PROCESSING_ALERT"
                                } else {
                                    viewModel.playAudio()
                                }
                            },
                            modifier = Modifier.clearAndSetSemantics {
                                role = Role.Button
                                contentDescription = btnText
                                liveRegion = LiveRegionMode.Polite
                            },
                            containerColor = if (audioState == AudioState.PROCESSING) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
                            icon = {
                                when (audioState) {
                                    AudioState.PROCESSING -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                    AudioState.PLAYING -> Icon(Icons.Default.Pause, null)
                                    else -> Icon(Icons.Default.PlayArrow, null)
                                }
                            },
                            text = { Text(btnText, fontWeight = FontWeight.Bold) }
                        )

                        IconButton(onClick = { viewModel.audioController.seekForward() }) {
                            Icon(Icons.Default.Forward10, contentDescription = "تقديم 10 ثواني")
                        }
                        IconButton(onClick = { viewModel.nextPage { activeBottomSheet = "BOOK_ENDED" } }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "الصفحة التالية")
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "صفحة $currentPageNumber من ${book?.totalPages ?: "?"}",
                        modifier = Modifier.semantics {
                            contentDescription = "صفحة $currentPageNumber من أصل ${book?.totalPages ?: "غير محدد"}"
                        }
                    )
                }
            }
        }
    ) { padding ->
        if (pageData == null) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.padding(32.dp)
                ) {
                    if (isFailed) {
                        Icon(Icons.Default.ErrorOutline, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
                        Text("توقفت المعالجة", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("واجه التطبيق مشكلة أثناء استخراج نصوص هذا الكتاب.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline)
                        Button(onClick = { navController.popBackStack() }) {
                            Text("العودة للمكتبة")
                        }
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Text("جاري معالجة الصفحة...", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                        Text("يتم استخراج نصوص هذه الصفحة عبر الذكاء الاصطناعي بالخلفية.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.outline)
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
                    itemsIndexed(paragraphs) { index, paragraph ->
                        val isHighlighted = index == highlightedIndex
                        Text(
                            text = paragraph,
                            style = MaterialTheme.typography.bodyLarge,
                            lineHeight = 32.sp,
                            color = if (isHighlighted) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isHighlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                                .padding(if (isHighlighted) 8.dp else 0.dp)
                                .semantics { if (isHighlighted) stateDescription = "يتم قراءتها الآن" }
                        )
                    }
                }
            }
        }
    }
}
