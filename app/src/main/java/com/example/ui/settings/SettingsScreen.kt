package com.example.ui.settings

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.repository.SettingsRepository
import com.example.gemini.GeminiClient
import com.example.tts.AudioSynthesisManager
import com.example.tts.EdgeTtsConstants
import androidx.compose.material.icons.filled.GraphicEq
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settingsRepository: SettingsRepository,
    audioSynthesisManager: AudioSynthesisManager,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val savedKey by settingsRepository.apiKey.collectAsState()
    var inputKey by remember(savedKey) { mutableStateOf(savedKey) }
    var keyVisible by remember { mutableStateOf(false) }
    var isTestingKey by remember { mutableStateOf(false) }

    val edgeVoiceId by settingsRepository.edgeVoiceId.collectAsState()
    val edgeSpeed by settingsRepository.edgeSpeed.collectAsState()
    val concurrency by settingsRepository.concurrency.collectAsState()
    val prefetchAheadCount by settingsRepository.prefetchAheadCount.collectAsState()
    val fallbackToNativeTts by settingsRepository.fallbackToNativeTts.collectAsState()

    var isTestingEdgeVoice by remember { mutableStateOf(false) }

    val edgeVoices = EdgeTtsConstants.AVAILABLE_VOICES

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("系统与车载配置", fontSize = 18.sp, fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("settings_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // 1. Microsoft Edge Neural TTS Engine Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                ),
                modifier = Modifier.fillMaxWidth().testTag("edge_tts_settings_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Edge 微软神经网络语音引擎", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "已全面替代 Android 自带机械音！由微软 Edge 神经网络大模型实时流式生成，拥有专业播音员级高保真音质，中英文发音自然醇厚、情感饱满，不限时零成本。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Engine Status
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Color(0xFF10B981).copy(alpha = 0.15f),
                                RoundedCornerShape(8.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "Edge 神经网络极速通道就绪 (24kHz 高保真 MP3)",
                            fontSize = 13.sp,
                            color = Color(0xFF047857),
                            fontWeight = FontWeight.Medium
                        )
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Voice Selection
                    Text("选择朗读音色 (微软官方 Neural Voice)", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(modifier = Modifier.height(8.dp))

                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        edgeVoices.forEach { voice ->
                            val isSelected = edgeVoiceId == voice.id
                            FilledTonalButton(
                                onClick = { settingsRepository.setEdgeVoiceId(voice.id) },
                                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .testTag("edge_voice_button_${voice.id}")
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        voice.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Text(
                                        voice.description,
                                        fontSize = 11.sp,
                                        color = if (isSelected) MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Speed Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("语速调节", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text("${String.format("%.2f", edgeSpeed)}x", fontSize = 13.sp, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }

                    Slider(
                        value = edgeSpeed,
                        onValueChange = { settingsRepository.setEdgeSpeed(it) },
                        valueRange = 0.5f..2.0f,
                        steps = 14,
                        modifier = Modifier.fillMaxWidth().testTag("edge_speed_slider")
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Test Voice Button
                    FilledTonalButton(
                        onClick = {
                            scope.launch {
                                isTestingEdgeVoice = true
                                Toast.makeText(context, "正在使用 Edge 神经网络合成试听音频...", Toast.LENGTH_SHORT).show()
                                audioSynthesisManager.speakAnswer("你好，我是车载伴读助手。当前已为您启用微软 Edge 神经网络语音，音质清晰自然，为您在驾驶途中带来沉浸悦耳的听书体验。") {
                                    isTestingEdgeVoice = false
                                }
                                isTestingEdgeVoice = false
                            }
                        },
                        enabled = !isTestingEdgeVoice,
                        modifier = Modifier.fillMaxWidth().testTag("test_edge_voice_button")
                    ) {
                        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(if (isTestingEdgeVoice) "Edge 语音合成中..." else "试听当前 Edge 神经网络音色", fontSize = 13.sp)
                    }
                }
            }

            // 2. Prefetch Concurrency Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().testTag("concurrency_settings_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("边播边预取并发 Worker: $concurrency 个", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "后台工作队列向后批量使用 Edge 微软神经网络语音预取未转换分块的并发数（推荐 2～3 个）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Slider(
                        value = concurrency.toFloat(),
                        onValueChange = { settingsRepository.setConcurrency(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                        modifier = Modifier.fillMaxWidth().testTag("concurrency_slider")
                    )
                }
            }

            // 3. Prefetch Ahead Chunk Count Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().testTag("prefetch_ahead_settings_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Speed, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("预处理缓存块数: $prefetchAheadCount 块", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "听书过程中向后超前预处理并落盘的最大分块数量（默认 5 块）。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Slider(
                        value = prefetchAheadCount.toFloat(),
                        onValueChange = { settingsRepository.setPrefetchAheadCount(it.toInt()) },
                        valueRange = 2f..30f,
                        steps = 27,
                        modifier = Modifier.fillMaxWidth().testTag("prefetch_ahead_slider")
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        listOf(3, 5, 10, 20).forEach { preset ->
                            FilledTonalButton(
                                onClick = { settingsRepository.setPrefetchAheadCount(preset) },
                                colors = androidx.compose.material3.ButtonDefaults.filledTonalButtonColors(
                                    containerColor = if (prefetchAheadCount == preset) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = if (prefetchAheadCount == preset) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text("$preset 块", fontSize = 11.sp)
                            }
                        }
                    }
                }
            }

            // 4. Gemini Companion QA Key Card (Only for Text QA, not TTS!)
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().testTag("api_key_settings_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Key, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Gemini 伴读答疑 API Key（仅问答）", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        "仅用于车载免手触智能答疑理解文本。听书朗读使用微软 Edge 神经网络语音，绝不消耗任何 Gemini 额度。",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    OutlinedTextField(
                        value = inputKey,
                        onValueChange = { inputKey = it },
                        label = { Text("Gemini API Key（选填，用于伴读文本答疑）") },
                        placeholder = { Text("AIzaSy...") },
                        visualTransformation = if (keyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(
                                onClick = { keyVisible = !keyVisible },
                                modifier = Modifier.testTag("toggle_key_visibility_button")
                            ) {
                                Icon(
                                    imageVector = if (keyVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (keyVisible) "隐藏" else "显示"
                                )
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("api_key_input_field")
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        FilledTonalButton(
                            onClick = {
                                scope.launch {
                                    val testKey = inputKey.trim().ifEmpty { settingsRepository.getEffectiveApiKey() }
                                    if (testKey.isEmpty()) {
                                        Toast.makeText(context, "请先输入 API Key", Toast.LENGTH_SHORT).show()
                                        return@launch
                                    }
                                    isTestingKey = true
                                    val result = GeminiClient.answerQuestion(
                                        apiKey = testKey,
                                        prevChunkText = "测试前序",
                                        currentChunkText = "正在进行 Gemini 伴读问答连通性测试。",
                                        userQuestion = "系统连接正常吗？"
                                    )
                                    isTestingKey = false
                                    if (result.isSuccess) {
                                        Toast.makeText(context, "✅ 答疑连接成功！回答: ${result.getOrThrow()}", Toast.LENGTH_LONG).show()
                                    } else {
                                        Toast.makeText(context, "❌ 测试失败: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            enabled = !isTestingKey,
                            modifier = Modifier.testTag("test_api_key_button")
                        ) {
                            Text(if (isTestingKey) "测试中..." else "测试答疑连通性", fontSize = 13.sp)
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Button(
                            onClick = {
                                settingsRepository.setApiKey(inputKey)
                                Toast.makeText(context, "配置已成功保存", Toast.LENGTH_SHORT).show()
                            },
                            modifier = Modifier.testTag("save_api_key_button")
                        ) {
                            Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("保存配置", fontSize = 13.sp)
                        }
                    }
                }
            }

            // 5. Offline Fallback Switch Card
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().testTag("offline_fallback_card")
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("系统原生 TTS 兜底保障", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "当网络短暂中断或无信号时，自动平滑切至 Android 本地原生 TTS，确保行车听书不中断；网络恢复后自动切回 Edge 神经网络语音。",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Switch(
                        checked = fallbackToNativeTts,
                        onCheckedChange = { settingsRepository.setFallbackToNativeTts(it) },
                        modifier = Modifier.testTag("fallback_switch")
                    )
                }
            }
        }
    }
}
