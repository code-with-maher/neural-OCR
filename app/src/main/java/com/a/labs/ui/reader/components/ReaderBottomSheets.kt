package com.a.labs.ui.reader.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderBottomSheets(
    activeSheet: String?,
    sheetState: SheetState,
    audioError: String?,
    onDismiss: () -> Unit,
    onConfirmDelete: () -> Unit,
    onClearAudioError: () -> Unit,
    onRestartBook: () -> Unit,
    onNavigateLibrary: () -> Unit,
    onStopProcessing: () -> Unit
) {
    if (activeSheet == null) return

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            when (activeSheet) {
                "DELETE_CONFIRM" -> {
                    Icon(
                        Icons.Default.DeleteForever,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("تأكيد الحذف", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("هل أنت متأكد أنك تريد حذف هذا الكتاب وكل محتوياته؟", textAlign = TextAlign.Center)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("إلغاء") }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = onConfirmDelete
                        ) {
                            Text("حذف")
                        }
                    }
                }

                "AUDIO_ERROR" -> {
                    Icon(
                        Icons.Default.ErrorOutline,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Text("خطأ في الصوت", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(audioError ?: "حدث خطأ غير معروف.", textAlign = TextAlign.Center)
                    Button(onClick = {
                        onClearAudioError()
                        onDismiss()
                    }) {
                        Text("حسناً")
                    }
                }

                "BOOK_ENDED" -> {
                    Icon(
                        Icons.Default.CheckCircle,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("لقد انتهى الكتاب!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text("تهانينا! لقد أتممت قراءة واستماع هذا الكتاب بنجاح.", textAlign = TextAlign.Center)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) { Text("إلغاء") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onRestartBook) { Text("إعادة القراءة") }
                        Spacer(Modifier.width(8.dp))
                        Button(onClick = onNavigateLibrary) { Text("المكتبة") }
                    }
                }

                "PROCESSING_ALERT" -> {
                    Icon(
                        Icons.Default.Memory,
                        null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text("الذكاء الاصطناعي في خضم العمل!", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Text(
                        "نقوم حالياً بتهيئة وهندسة النصوص صوتياً في الخلفية لضمان تجربة استماع مثالية. هل تود إيقاف هذه العملية؟",
                        textAlign = TextAlign.Center
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End
                    ) {
                        TextButton(onClick = onDismiss) {
                            Text("لا، دعه يستمر بالإبداع")
                        }
                        Spacer(Modifier.width(8.dp))
                        Button(
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            onClick = onStopProcessing
                        ) {
                            Text("إيقاف المعالجة")
                        }
                    }
                }
            }
        }
    }
}