package com.example.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.models.BookEntity
import com.example.data.models.ChunkEntity
import com.example.data.repository.BookRepository
import com.example.data.repository.SettingsRepository
import com.example.pipeline.TtsPrefetchPipeline
import com.example.statemachine.InteractionStateMachine
import com.example.statemachine.PlayerState
import com.example.tts.AudioSynthesisManager
import com.example.tts.EdgeTtsConstants
import androidx.compose.material.icons.filled.GraphicEq

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DrivingPlayerScreen(
    bookId: String,
    bookRepository: BookRepository,
    settingsRepository: SettingsRepository,
    stateMachine: InteractionStateMachine,
    prefetchPipeline: TtsPrefetchPipeline,
    audioSynthesisManager: AudioSynthesisManager,
    onBack: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToErrors: () -> Unit
) {
    val book by bookRepository.getBookFlow(bookId).collectAsState(initial = null)
    val chunks by bookRepository.getChunksFlow(bookId).collectAsState(initial = emptyList())

    val currentState by stateMachine.currentState.collectAsState()
    val currentChunkIndex by stateMachine.currentChunkIndex.collectAsState()
    val currentPositionMs by stateMachine.currentPositionMs.collectAsState()
    val chunkDurationMs by stateMachine.chunkDurationMs.collectAsState()
    val stateMessage by stateMachine.stateMessage.collectAsState()
    val lastQuestion by stateMachine.lastUserQuestion.collectAsState()
    val lastAnswer by stateMachine.lastAiAnswer.collectAsState()
    val pipelineStatus by prefetchPipeline.pipelineStatus.collectAsState()
    val playerLastError by com.example.service.AudioPlayerManager.lastError.collectAsState()
    val systemVolumePercent by com.example.service.AudioPlayerManager.systemVolumePercent.collectAsState()
    val edgeVoiceId by settingsRepository.edgeVoiceId.collectAsState()
    val recognizedQuestionText by stateMachine.voiceManager.recognizedPartialText.collectAsState()
    val questionSilencePrompt by stateMachine.voiceManager.questionSilencePrompt.collectAsState()
    val isMicActive by stateMachine.voiceManager.isMicActive.collectAsState()
    val coroutineScope = rememberCoroutineScope()

    // Start pipeline and load book on first mount
    LaunchedEffect(bookId) {
        stateMachine.loadBook(bookId, book?.currentChunkIndex ?: 0)
        prefetchPipeline.startPipeline(bookId, book?.currentChunkIndex ?: 0)
    }

    val currentChunk = chunks.getOrNull(currentChunkIndex)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = book?.title ?: "车载随身听书",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "车载全语音交互 · 分块 ${currentChunkIndex + 1} / ${chunks.size}",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack, modifier = Modifier.testTag("player_back_button")) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            coroutineScope.launch {
                                stateMachine.pause()
                                bookRepository.clearAudioCacheForBook(bookId)
                                prefetchPipeline.startPipeline(bookId, currentChunkIndex)
                                stateMachine.loadBook(bookId, currentChunkIndex)
                            }
                        },
                        modifier = Modifier.testTag("player_regenerate_tts_button")
                    ) {
                        Icon(
                            Icons.Default.AutoAwesome,
                            contentDescription = "用 Edge 神经网络重新合成全书",
                            tint = Color(0xFF10B981)
                        )
                    }
                    IconButton(
                        onClick = { com.example.service.AudioPlayerManager.playTestChime() },
                        modifier = Modifier.testTag("player_test_chime_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = "试听真实人声（你好，我是车载伴读助手）",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    IconButton(onClick = onNavigateToErrors, modifier = Modifier.testTag("player_errors_button")) {
                        Icon(Icons.Default.BugReport, contentDescription = "错误日志", tint = Color(0xFFEF4444))
                    }
                    IconButton(onClick = onNavigateToSettings, modifier = Modifier.testTag("player_settings_button")) {
                        Icon(Icons.Default.Settings, contentDescription = "设置")
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
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. Prominent 5-State Machine Automotive HUD Badge
            StateMachineHudBadge(
                state = currentState,
                message = stateMessage
            )

            // 1.1 Active Voice Listening & Endpointing Card (Real-time STT text, Criteria countdown & Immediate Action buttons)
            AnimatedVisibility(visible = currentState == PlayerState.LISTENING) {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = Color(0xFF1E293B)
                    ),
                    border = BorderStroke(2.dp, Color(0xFFEF4444)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("active_listening_question_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .background(Color(0xFFEF4444), CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "车载伴读正在倾听提问",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF87171)
                            )
                            Spacer(modifier = Modifier.weight(1f))
                            Text(
                                text = if (questionSilencePrompt.isNotBlank()) questionSilencePrompt else "说完停顿 1.5 秒自动提交",
                                fontSize = 11.sp,
                                color = Color(0xFF38BDF8)
                            )
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Real-time spoken text display
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFF0F172A),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(
                                    text = if (recognizedQuestionText.isNotBlank()) "“$recognizedQuestionText”" else "请说话，例如：“这段话的主角是谁？”",
                                    fontSize = 15.sp,
                                    fontWeight = if (recognizedQuestionText.isNotBlank()) FontWeight.Bold else FontWeight.Normal,
                                    color = if (recognizedQuestionText.isNotBlank()) Color.White else Color(0xFF94A3B8),
                                    lineHeight = 22.sp
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Default.Mic,
                                        contentDescription = null,
                                        tint = if (isMicActive) Color(0xFF10B981) else Color(0xFF64748B),
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "智能判定准则：疑问句停顿 1.0s / 陈述句停顿 1.5s / 说“我说完了”立即提交",
                                        fontSize = 10.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Quick actions for Driver: Submit now or Cancel
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = {
                                    val q = recognizedQuestionText.trim().ifBlank { "请简要解释当前段落的背景与重点" }
                                    stateMachine.voiceManager.finalizeQuestion(q)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF10B981)),
                                modifier = Modifier
                                    .weight(1.2f)
                                    .height(44.dp)
                                    .testTag("submit_question_now_button")
                            ) {
                                Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("我说完了 · 立即解答", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            }

                            OutlinedButton(
                                onClick = {
                                    stateMachine.handleResume()
                                },
                                modifier = Modifier
                                    .weight(0.8f)
                                    .height(44.dp)
                                    .testTag("cancel_question_resume_button")
                            ) {
                                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(14.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("取消·继续", fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // Edge Neural TTS Indicator Banner
            val currentVoiceName = EdgeTtsConstants.AVAILABLE_VOICES.find { it.id == edgeVoiceId }?.name ?: "云希"
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFF10B981).copy(alpha = 0.15f),
                modifier = Modifier.fillMaxWidth().testTag("player_tts_banner")
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.GraphicEq,
                            contentDescription = null,
                            tint = Color(0xFF10B981),
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "当前引擎: Edge 微软神经网络语音 ($currentVoiceName)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color(0xFF047857)
                        )
                    }
                    TextButton(
                        onClick = {
                            coroutineScope.launch {
                                stateMachine.pause()
                                bookRepository.clearAudioCacheForBook(bookId)
                                prefetchPipeline.startPipeline(bookId, currentChunkIndex)
                                stateMachine.loadBook(bookId, currentChunkIndex)
                            }
                        },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                        modifier = Modifier.testTag("player_re_synthesize_button")
                    ) {
                        Text(
                            text = "用 Edge 重合成",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            if (playerLastError != null) {
                Card(
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7F1D1D)),
                    modifier = Modifier.fillMaxWidth().testTag("player_error_card")
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFF87171))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = playerLastError ?: "",
                            fontSize = 12.sp,
                            color = Color.White,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 2. Waveform & Voice Interaction Visualizer
            WaveformVisualizer(
                state = currentState,
                isMicActive = stateMachine.voiceManager.isMicActive.collectAsState().value
            )

            // 3. Quick Simulation Bar (To satisfy DoD hands-free testing & noisy cabin testing)
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                modifier = Modifier.fillMaxWidth().testTag("simulation_card")
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "车载全双工语音指令模拟（免打扰调试区）：",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        FilledTonalButton(
                            onClick = { stateMachine.voiceManager.simulateInterrupt() },
                            modifier = Modifier.weight(1f).testTag("sim_say_stop_button"),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("🗣️ 说“停一下”", fontSize = 11.sp)
                        }

                        FilledTonalButton(
                            onClick = { stateMachine.voiceManager.simulateQuestion("这段话的主要背景和核心概念是什么？") },
                            modifier = Modifier.weight(1f).testTag("sim_say_question_button"),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("❓ 语音提问", fontSize = 11.sp)
                        }

                        FilledTonalButton(
                            onClick = { stateMachine.voiceManager.simulateResume() },
                            modifier = Modifier.weight(1f).testTag("sim_say_resume_button"),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            Text("🗣️ 说“继续”", fontSize = 11.sp)
                        }
                    }
                }
            }

            // 4. Companion Q&A Card (Appears during/after voice interaction)
            AnimatedVisibility(visible = lastQuestion.isNotBlank()) {
                Card(
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E293B)),
                    modifier = Modifier.fillMaxWidth().testTag("companion_dialog_card")
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Hearing, contentDescription = null, tint = Color(0xFF38BDF8), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("驾驶员打断提问：", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF38BDF8))
                        }
                        Text(
                            text = lastQuestion,
                            fontSize = 14.sp,
                            color = Color.White,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.RecordVoiceOver, contentDescription = null, tint = Color(0xFF34D399), modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("伴读助手解答（口语化面向听觉）：", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF34D399))
                        }
                        Text(
                            text = if (lastAnswer.isNotBlank()) lastAnswer else "正在实时生成回答...",
                            fontSize = 14.sp,
                            color = Color(0xFFF1F5F9),
                            lineHeight = 20.sp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                }
            }

            // 5. Current Chunk Text Display
            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().testTag("current_chunk_card")
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "正文分块 #${currentChunkIndex + 1}",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "状态: ${currentChunk?.status ?: "PENDING"}",
                            fontSize = 11.sp,
                            color = if (currentChunk?.status == ChunkEntity.STATUS_COMPLETED) Color(0xFF10B981) else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = currentChunk?.textContent ?: "分块文本加载中...",
                        fontSize = 15.sp,
                        lineHeight = 22.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            // 6. Playback Progress
            val progressFraction = if (chunkDurationMs > 0) (currentPositionMs.toFloat() / chunkDurationMs).coerceIn(0f, 1f) else 0f
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                LinearProgressIndicator(
                    progress = { progressFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(formatTime(currentPositionMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatTime(chunkDurationMs), fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // 7. Large In-Car Driving Touch Controls (Touch targets > 64dp)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous Chunk
                FilledIconButton(
                    onClick = { stateMachine.skipPreviousChunk() },
                    modifier = Modifier.size(52.dp).testTag("prev_chunk_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(Icons.Default.SkipPrevious, contentDescription = "上一块", modifier = Modifier.size(28.dp))
                }

                // Rewind 15s
                FilledIconButton(
                    onClick = { stateMachine.seekRelative(-15) },
                    modifier = Modifier.size(52.dp).testTag("rewind_15s_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(Icons.Default.FastRewind, contentDescription = "快退15秒", modifier = Modifier.size(26.dp))
                }

                // Center Main Driving Play / Pause Toggle Button (72dp)
                val isPlayingNow = currentState == PlayerState.PLAYING || currentState == PlayerState.ANSWERING
                Surface(
                    onClick = {
                        stateMachine.togglePlayPause()
                    },
                    shape = CircleShape,
                    color = if (isPlayingNow) MaterialTheme.colorScheme.primary else Color(0xFF10B981),
                    shadowElevation = 6.dp,
                    modifier = Modifier.size(72.dp).testTag("main_driving_action_button")
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = if (isPlayingNow) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlayingNow) "暂停播放" else "开始播放",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }

                // Forward 15s
                FilledIconButton(
                    onClick = { stateMachine.seekRelative(15) },
                    modifier = Modifier.size(52.dp).testTag("forward_15s_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(Icons.Default.FastForward, contentDescription = "快进15秒", modifier = Modifier.size(26.dp))
                }

                // Next Chunk
                FilledIconButton(
                    onClick = { stateMachine.skipNextChunk() },
                    modifier = Modifier.size(52.dp).testTag("next_chunk_button"),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Icon(Icons.Default.SkipNext, contentDescription = "下一块", modifier = Modifier.size(28.dp))
                }
            }

            // 8. Prefetch Pipeline Status Banner
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "流水线状态: $pipelineStatus",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun StateMachineHudBadge(
    state: PlayerState,
    message: String
) {
    val infiniteTransition = rememberInfiniteTransition()
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    val (badgeTitle, badgeColor, icon) = when (state) {
        PlayerState.PLAYING -> Triple("PLAYING · 正在朗读正文", Color(0xFF0284C7), Icons.Default.PlayArrow)
        PlayerState.LISTENING -> Triple("LISTENING · 倾听驾驶员提问", Color(0xFFEF4444), Icons.Default.Hearing)
        PlayerState.THINKING -> Triple("THINKING · 伴读助手思考中", Color(0xFF8B5CF6), Icons.Default.Psychology)
        PlayerState.ANSWERING -> Triple("ANSWERING · 语音播报解答", Color(0xFF10B981), Icons.Default.RecordVoiceOver)
        PlayerState.RESUMING -> Triple("RESUMING · 正在恢复正文", Color(0xFF059669), Icons.Default.Replay)
        PlayerState.IDLE -> Triple("IDLE · 待命中", Color(0xFF64748B), Icons.Default.Pause)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = badgeColor.copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, badgeColor),
        modifier = Modifier
            .fillMaxWidth()
            .scale(if (state == PlayerState.LISTENING || state == PlayerState.THINKING) pulseScale else 1f)
            .testTag("state_machine_hud_badge")
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .background(badgeColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = badgeTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = badgeColor
                )
                Text(
                    text = message,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun WaveformVisualizer(
    state: PlayerState,
    isMicActive: Boolean
) {
    val transition = rememberInfiniteTransition()
    val wave1 by transition.animateFloat(
        initialValue = 8f,
        targetValue = 38f,
        animationSpec = infiniteRepeatable(tween(400, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    val wave2 by transition.animateFloat(
        initialValue = 18f,
        targetValue = 44f,
        animationSpec = infiniteRepeatable(tween(550, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    val wave3 by transition.animateFloat(
        initialValue = 12f,
        targetValue = 32f,
        animationSpec = infiniteRepeatable(tween(350, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )
    val wave4 by transition.animateFloat(
        initialValue = 20f,
        targetValue = 48f,
        animationSpec = infiniteRepeatable(tween(480, easing = FastOutSlowInEasing), RepeatMode.Reverse)
    )

    val waveColor = when (state) {
        PlayerState.PLAYING -> Color(0xFF38BDF8)
        PlayerState.LISTENING -> Color(0xFFEF4444)
        PlayerState.THINKING -> Color(0xFFA78BFA)
        PlayerState.ANSWERING -> Color(0xFF34D399)
        else -> Color(0xFF64748B)
    }

    val isActive = state != PlayerState.IDLE

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(Color(0xFF0F172A), RoundedCornerShape(14.dp))
            .padding(horizontal = 24.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val heights = if (isActive) {
            listOf(wave1, wave2, wave4, wave3, wave2, wave1, wave3, wave4, wave2)
        } else {
            listOf(8f, 10f, 12f, 10f, 8f, 10f, 12f, 10f, 8f)
        }

        heights.forEach { h ->
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .width(4.dp)
                    .height(h.dp)
                    .background(waveColor, CircleShape)
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}
