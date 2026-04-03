package com.a.labs.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
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
import com.a.labs.data.local.SettingsManager
import com.a.labs.core.GeminiModels
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(navController: NavHostController) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settingsManager = remember { SettingsManager(context) }

    val geminiKey by settingsManager.geminiKey.collectAsState(initial = "")
    val elevenKey by settingsManager.elevenKey.collectAsState(initial = "")
    val selectedEngine by settingsManager.ttsEngine.collectAsState(initial = "SYSTEM")
    val selectedGeminiModel by settingsManager.geminiModel.collectAsState(initial = GeminiModels.FLASH_2_5)

    var engineExpanded by remember { mutableStateOf(false) }
    var modelExpanded by remember { mutableStateOf(false) }
    
    val engines = listOf("Gemini 2.5 Flash TTS", "ElevenLabs", "System Engine")

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("الإعدادات", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            Text("مفاتيح API", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            
            OutlinedTextField(
                value = geminiKey,
                onValueChange = { scope.launch { settingsManager.saveSetting(SettingsManager.GEMINI_KEY, it) } },
                label = { Text("Gemini API Key") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                singleLine = true
            )

            OutlinedTextField(
                value = elevenKey,
                onValueChange = { scope.launch { settingsManager.saveSetting(SettingsManager.ELEVEN_KEY, it) } },
                label = { Text("ElevenLabs API Key") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                singleLine = true
            )

            HorizontalDivider()

            Text("إعدادات الذكاء الاصطناعي", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

            ExposedDropdownMenuBox(
                expanded = modelExpanded,
                onExpandedChange = { modelExpanded = !modelExpanded }
            ) {
                OutlinedTextField(
                    value = selectedGeminiModel,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("نموذج Gemini المستهدف") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = modelExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = modelExpanded,
                    onDismissRequest = { modelExpanded = false }
                ) {
                    GeminiModels.availableModels.forEach { model ->
                        DropdownMenuItem(
                            text = { Text(model) },
                            onClick = {
                                scope.launch { settingsManager.saveSetting(SettingsManager.GEMINI_MODEL_KEY, model) }
                                modelExpanded = false
                            }
                        )
                    }
                }
            }

            ExposedDropdownMenuBox(
                expanded = engineExpanded,
                onExpandedChange = { engineExpanded = !engineExpanded }
            ) {
                OutlinedTextField(
                    value = selectedEngine,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("محرك النطق (TTS)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = engineExpanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = engineExpanded,
                    onDismissRequest = { engineExpanded = false }
                ) {
                    engines.forEach { engine ->
                        DropdownMenuItem(
                            text = { Text(engine) },
                            onClick = {
                                scope.launch { settingsManager.saveSetting(SettingsManager.TTS_ENGINE, engine) }
                                engineExpanded = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("حول التطبيق", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("الإصدار: 1.0.0-Alpha", fontSize = 14.sp)
                    Text(
                        "مشروع قراءة الكتب والتعرف البصري المدعوم بالذكاء الاصطناعي.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}
