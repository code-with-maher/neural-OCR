package com.a.labs.ui.reader.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.a.labs.data.local.room.entity.BookEntity
import com.a.labs.data.local.room.entity.PageEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderTopBar(
    book: BookEntity?,
    pageData: PageEntity?,
    onBackClick: () -> Unit,
    onNavigateSettings: () -> Unit,
    onExportAudio: () -> Unit,
    onDeleteBookClick: () -> Unit
) {
    val context = LocalContext.current
    var showMenu by remember { mutableStateOf(false) }

    TopAppBar(
        title = {
            Text(
                text = book?.title ?: "جاري التحميل...",
                maxLines = 1,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
        },
        navigationIcon = {
            IconButton(onClick = onBackClick) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "رجوع")
            }
        },
        actions = {
            Box {
                IconButton(onClick = { showMenu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "خيارات إضافية")
                }
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("نسخ النص") },
                        onClick = {
                            showMenu = false
                            pageData?.markdownContent?.let {
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                clipboard.setPrimaryClip(ClipData.newPlainText("Text", it))
                                Toast.makeText(context, "تم النسخ", Toast.LENGTH_SHORT).show()
                            }
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("الإعدادات") },
                        onClick = {
                            showMenu = false
                            onNavigateSettings()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("تحميل الصوت") },
                        onClick = {
                            showMenu = false
                            onExportAudio()
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("حذف الكتاب", color = MaterialTheme.colorScheme.error) },
                        onClick = {
                            showMenu = false
                            onDeleteBookClick()
                        }
                    )
                }
            }
        }
    )
}