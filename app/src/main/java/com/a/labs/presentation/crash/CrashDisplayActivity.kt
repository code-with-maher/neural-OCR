package com.a.labs.presentation.crash

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

class CrashDisplayActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // جلب تفاصيل الخطأ من الـ Intent
        val crashDetails = intent.getStringExtra("ERROR_DETAILS") ?: "خطأ غير معروف، اختفى الكراش بطريقة غامضة!"

        setContent {
            MaterialTheme {
                CrashScreen(crashDetails = crashDetails)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CrashScreen(crashDetails: String) {
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("أوبس! التطبيق تعثر") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    titleContentColor = MaterialTheme.colorScheme.onErrorContainer
                ),
                navigationIcon = {
                    Icon(
                        imageVector = Icons.Default.Warning,
                        contentDescription = "أيقونة تحذير، حدث خطأ",
                        modifier = Modifier.padding(horizontal = 12.dp),
                        tint = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = {
                    clipboardManager.setText(AnnotatedString(crashDetails))
                    Toast.makeText(context, "تم نسخ تقرير الخطأ بنجاح", Toast.LENGTH_SHORT).show()
                },
                containerColor = MaterialTheme.colorScheme.primary,
                // دعم إمكانية الوصول: وصف الزر لقارئات الشاشة
                modifier = Modifier.semantics {
                    contentDescription = "زر نسخ تقرير الخطأ بالكامل إلى الحافظة"
                }
            ) {
                Icon(
                    imageVector = Icons.Default.ContentCopy,
                    contentDescription = null // تم وضع الوصف في الـ Modifier الأب
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
        ) {
            Text(
                text = "حدث خطأ غير متوقع وتم التقاطه. يمكنك نسخ التقرير أدناه:",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 16.dp)
            )

            // حاوية تتيح تحديد النص يدوياً (مفيدة جداً للمطورين)
            SelectionContainer(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = MaterialTheme.shapes.medium
                    )
                    .padding(12.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = crashDetails,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace // خط المبرمجين الواضح
                    ),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // دعم إمكانية الوصول: قراءة محتوى الخطأ
                    modifier = Modifier.semantics {
                        contentDescription = "تفاصيل الخطأ البرمجي: $crashDetails"
                    }
                )
            }
        }
    }
}
