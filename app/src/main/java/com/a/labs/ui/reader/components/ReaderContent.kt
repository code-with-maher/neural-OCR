package com.a.labs.ui.reader.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.a.labs.data.local.room.entity.PageEntity

@Composable
fun ReaderContent(
    modifier: Modifier = Modifier,
    pageData: PageEntity?,
    isProcessing: Boolean,
    isFailed: Boolean,
    highlightedIndex: Int,
    onNavigateBack: () -> Unit
) {
    if (pageData == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.padding(32.dp)
            ) {
                if (isFailed) {
                    Icon(
                        Icons.Default.ErrorOutline,
                        null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("توقفت المعالجة", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        "واجه التطبيق مشكلة أثناء استخراج نصوص هذا الكتاب.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Button(onClick = onNavigateBack) {
                        Text("العودة للمكتبة")
                    }
                } else {
                    CircularProgressIndicator(modifier = Modifier.size(48.dp))
                    Text("جاري معالجة الصفحة...", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text(
                        "يتم استخراج نصوص هذه الصفحة عبر الذكاء الاصطناعي بالخلفية.",
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.outline
                    )
                }
            }
        }
    } else {
        val content = pageData.markdownContent
        if (content == "الصفحة فارغة") {
            Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
                modifier = modifier.fillMaxSize(),
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
                            .background(
                                if (isHighlighted) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
                                RoundedCornerShape(8.dp)
                            )
                            .padding(if (isHighlighted) 8.dp else 0.dp)
                            .semantics {
                                if (isHighlighted) stateDescription = "يتم قراءتها الآن"
                            }
                    )
                }
            }
        }
    }
}