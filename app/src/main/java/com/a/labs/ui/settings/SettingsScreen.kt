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

    var expanded by remember { mutableStateOf(false) }
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
                label = { Text("ElevenLabs API Key (اختياري)") },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                singleLine = true
            )

            HorizontalDivider()

            Text("محرك النطق", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)

            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = !expanded }
            ) {
                OutlinedTextField(
                    value = selectedEngine,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("اختر المحرك") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
                    modifier = Modifier.menuAnchor().fillMaxWidth()
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    engines.forEach { engine ->
                        DropdownMenuItem(
                            text = { Text(engine) },
                            onClick = {
                                scope.launch { settingsManager.saveSetting(SettingsManager.TTS_ENGINE, engine) }
                                expanded = false
                            }
                        )
                    }
                }
            }

            HorizontalDivider()

            Button(
                onClick = { /* التحقق من التحديثات */ },
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(12.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("التحقق من وجود تحديثات")
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("حول التطبيق", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("الإصدار: 1.0.0-Alpha", fontSize = 14.sp)
                    Text(
                        "هذا التطبيق مخصص لمساعدة ذوي الاحتياجات الخاصة على قراءه الكتب والوصول للمحتوى الصوتي بسهولة.",
                        fontSize = 13.sp,
                        lineHeight = 18.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { /* فتح رابط التليجرام */ }, modifier = Modifier.align(Alignment.End)) {
                        Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("انضم لقناتنا على تليجرام")
                    }
                }
            }
        }
    }
}
