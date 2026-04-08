package com.a.labs.ui.settings

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.a.labs.core.GeminiModels
import com.a.labs.data.local.SettingsManager
import kotlinx.coroutines.launch

@Suppress("DEPRECATION")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }

    val geminiKey by settingsManager.geminiKey.collectAsState("")
    val elevenKey by settingsManager.elevenKey.collectAsState("")
    val selectedEngine by settingsManager.ttsEngine.collectAsState("SYSTEM")
    val selectedModel by settingsManager.geminiModel.collectAsState(GeminiModels.FLASH_3_1_LITE)
    val chunkSize by settingsManager.chunkSize.collectAsState(15)
    val devModeUnlocked by settingsManager.isDevModeUnlocked.collectAsState(false)
    val loggingEnabled by settingsManager.isLoggingEnabled.collectAsState(false)

    var showGeminiDialog by remember { mutableStateOf(false) }
    var showElevenDialog by remember { mutableStateOf(false) }
    var tempGeminiKey by remember { mutableStateOf("") }
    var tempElevenKey by remember { mutableStateOf("") }

    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    
    var versionTaps by remember { mutableIntStateOf(0) }
    val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
    val versionName = packageInfo.versionName ?: "1.0"

    val engines = listOf("SYSTEM", "ELEVENLABS", "GEMINI_TTS")

    if (showGeminiDialog) {
        AlertDialog(
            onDismissRequest = { showGeminiDialog = false },
            title = { Text("Gemini API Key") },
            text = {
                OutlinedTextField(
                    value = tempGeminiKey,
                    onValueChange = { tempGeminiKey = it },
                    label = { Text("المفتاح الخاص بك") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                    scope.launch { settingsManager.saveSetting(SettingsManager.GEMINI_KEY, tempGeminiKey) }
                    showGeminiDialog = false
                    Toast.makeText(context, "تم حفظ المفتاح بنجاح", Toast.LENGTH_SHORT).show()
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showGeminiDialog = false }) { Text("إلغاء") }
            }
        )
    }

    if (showElevenDialog) {
        AlertDialog(
            onDismissRequest = { showElevenDialog = false },
            title = { Text("ElevenLabs API Key") },
            text = {
                OutlinedTextField(
                    value = tempElevenKey,
                    onValueChange = { tempElevenKey = it },
                    label = { Text("المفتاح الخاص بك") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
            },
            confirmButton = {
                Button(onClick = {
                     scope.launch { settingsManager.saveSetting(SettingsManager.ELEVEN_KEY, tempElevenKey) }
                    showElevenDialog = false
                    Toast.makeText(context, "تم حفظ المفتاح بنجاح", Toast.LENGTH_SHORT).show()
                }) { Text("حفظ") }
            },
            dismissButton = {
                TextButton(onClick = { showElevenDialog = false }) { Text("إلغاء") }
            }
        )
    }

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("الإعدادات", fontWeight = FontWeight.Black) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null)
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                Text("مفاتيح الوصول (API Keys)", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(
                            headlineContent = { Text("Gemini API") },
                            supportingContent = { Text(if (geminiKey.isNotBlank()) "تمت الإضافة" else "لم يتم التكوين") },
                            leadingContent = { Icon(Icons.Default.Key, null) },
                            modifier = Modifier.clickable {
                                tempGeminiKey = geminiKey
                                showGeminiDialog = true
                            }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("ElevenLabs API") },
                            supportingContent = { Text(if (elevenKey.isNotBlank()) "تمت الإضافة" else "لم يتم التكوين") },
                            leadingContent = { Icon(Icons.Default.VpnKey, null) },
                            modifier = Modifier.clickable {
                                tempElevenKey = elevenKey
                                showElevenDialog = true
                            }
                        )
                    }
                }
            }

            item {
                Text("الذكاء والمحركات", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = !modelExpanded }) {
                            OutlinedTextField(
                                value = selectedModel,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("نموذج الرؤية (OCR)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(modelExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = modelExpanded, onDismissRequest = { modelExpanded = false }) {
                                GeminiModels.availableModels.forEach { model ->
                                    DropdownMenuItem(text = { Text(model) }, onClick = {
                                        scope.launch { settingsManager.saveSetting(SettingsManager.GEMINI_MODEL_KEY, model) }
                                        modelExpanded = false
                                    })
                                }
                             }
                        }

                        ExposedDropdownMenuBox(expanded = engineExpanded, onExpandedChange = { engineExpanded = !engineExpanded }) {
                            OutlinedTextField(
                                value = selectedEngine,
                                onValueChange = {},
                                readOnly = true,
                                label = { Text("محرك النطق (TTS)") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(engineExpanded) },
                                modifier = Modifier.menuAnchor().fillMaxWidth()
                            )
                            ExposedDropdownMenu(expanded = engineExpanded, onDismissRequest = { engineExpanded = false }) {
                                engines.forEach { engine ->
                                    DropdownMenuItem(text = { Text(engine) }, onClick = {
                                        scope.launch { settingsManager.saveSetting(SettingsManager.TTS_ENGINE, engine) }
                                        engineExpanded = false
                                    })
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text("أداء المعالجة", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text("حجم الدفعة: $chunkSize صفحات", fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(8.dp))
                        Slider(
                            value = chunkSize.toFloat(),
                            onValueChange = { scope.launch { settingsManager.saveSetting(SettingsManager.CHUNK_SIZE_KEY, it.toInt()) } },
                            valueRange = 5f..40f,
                            steps = 6
                        )
                    }
                }
            }

            if (devModeUnlocked) {
                item {
                    Text("وضع المطور", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(start = 8.dp))
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column {
                            ListItem(
                                headlineContent = { Text("تسجيل الأحداث (Logging)", color = MaterialTheme.colorScheme.onErrorContainer) },
                                supportingContent = { Text("يساعد في تتبع الأخطاء. إيقافه يوفر الموارد.", color = MaterialTheme.colorScheme.onErrorContainer) },
                                trailingContent = {
                                    Switch(
                                        checked = loggingEnabled,
                                        onCheckedChange = { isChecked ->
                                            scope.launch { settingsManager.saveSetting(SettingsManager.LOGGING_ENABLED, isChecked) }
                                        }
                                    )
                                },
                                colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                            )
                            HorizontalDivider()
                            ListItem(
                                headlineContent = { Text("عرض سجلات النظام", color = MaterialTheme.colorScheme.onErrorContainer) },
                                leadingContent = { Icon(Icons.Default.BugReport, null, tint = MaterialTheme.colorScheme.onErrorContainer) },
                                modifier = Modifier.clickable { navController.navigate("logs") },
                                 colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent)
                            )
                        }
                    }
                }
            }

            item {
                Text("حول التطبيق", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column {
                        ListItem(
                            headlineContent = { Text("تواصل معنا عبر واتساب") },
                            leadingContent = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
                            modifier = Modifier.clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/963946709091"))
                                context.startActivity(intent)
                            }
                        )
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text("الإصدار") },
                            supportingContent = { Text(versionName) },
                            leadingContent = { Icon(Icons.Default.Info, null) },
                            modifier = Modifier.clickable {
                                if (!devModeUnlocked) {
                                    versionTaps++
                                    if (versionTaps >= 5) {
                                        scope.launch { settingsManager.saveSetting(SettingsManager.DEV_MODE_UNLOCKED, true) }
                                        Toast.makeText(context, "تم تفعيل وضع المطور", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }
     }
}