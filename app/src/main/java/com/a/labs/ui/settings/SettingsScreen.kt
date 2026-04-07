package com.a.labs.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.a.labs.core.GeminiModels
import com.a.labs.data.local.SettingsManager
import kotlinx.coroutines.launch

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

    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }

    val engines = listOf("SYSTEM", "ELEVENLABS", "GEMINI_TTS")

    Scaffold(
        topBar = {
            LargeTopAppBar(
                title = { Text("التكوين والذكاء", fontWeight = FontWeight.Black) },
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
                Text("مفاتيح الوصول", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = geminiKey,
                            onValueChange = { scope.launch { settingsManager.saveSetting(SettingsManager.GEMINI_KEY, it) } },
                            label = { Text("Gemini API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.Key, null) }
                        )
                        OutlinedTextField(
                            value = elevenKey,
                            onValueChange = { scope.launch { settingsManager.saveSetting(SettingsManager.ELEVEN_KEY, it) } },
                            label = { Text("ElevenLabs API Key") },
                            modifier = Modifier.fillMaxWidth(),
                            leadingIcon = { Icon(Icons.Default.VpnKey, null) }
                        )
                    }
                }
            }

            item {
                Text("محركات المعالجة", fontSize = 12.sp, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 8.dp))
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        ExposedDropdownMenuBox(expanded = modelExpanded, onExpandedChange = { modelExpanded = !modelExpanded }) {
                            OutlinedTextField(
                                value =  selectedModel,
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
                        Text("حجم دفعة الصفحات: $chunkSize", fontSize = 14.sp)
                        Slider(
                            value = chunkSize.toFloat(),
                            onValueChange = { scope.launch { settingsManager.saveSetting(SettingsManager.CHUNK_SIZE_KEY, it.toInt()) } },
                            valueRange = 5f..40f,
                            steps = 6
                        )
                    }
                }
            }
        }
     }
}