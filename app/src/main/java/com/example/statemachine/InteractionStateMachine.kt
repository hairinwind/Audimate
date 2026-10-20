package com.example.statemachine

import android.content.Context
import com.example.data.models.ChunkEntity
import com.example.data.repository.BookRepository
import com.example.data.repository.SettingsRepository
import com.example.gemini.GeminiClient
import com.example.logging.AppLogger
import com.example.service.AudioPlayerManager
import com.example.service.AudiobookMediaService
import com.example.tts.AudioSynthesisManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class PlayerState {
    IDLE,
    PLAYING,    // 朗读正文，监听打断词
    LISTENING,  // 倾听提问，连续语音识别
    THINKING,   // 伴读思考，上下文组装与推理
    ANSWERING,  // 播报答疑，TTS答疑播放
    RESUMING    // 续播正文，捕获确认指令，无缝返回原位置
}

class InteractionStateMachine(
    private val context: Context,
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository,
    private val audioSynthesisManager: AudioSynthesisManager
) {
    private val scope = CoroutineScope(Dispatchers.Main)

    private val _currentState = MutableStateFlow(PlayerState.IDLE)
    val currentState: StateFlow<PlayerState> = _currentState.asStateFlow()

    private val _currentBookId = MutableStateFlow<String?>(null)
    val currentBookId: StateFlow<String?> = _currentBookId.asStateFlow()

    private val _currentChunkIndex = MutableStateFlow(0)
    val currentChunkIndex: StateFlow<Int> = _currentChunkIndex.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _chunkDurationMs = MutableStateFlow(0L)
    val chunkDurationMs: StateFlow<Long> = _chunkDurationMs.asStateFlow()

    private val _lastUserQuestion = MutableStateFlow("")
    val lastUserQuestion: StateFlow<String> = _lastUserQuestion.asStateFlow()

    private val _lastAiAnswer = MutableStateFlow("")
    val lastAiAnswer: StateFlow<String> = _lastAiAnswer.asStateFlow()

    private val _stateMessage = MutableStateFlow("准备就绪")
    val stateMessage: StateFlow<String> = _stateMessage.asStateFlow()

    // Precise pause timestamp for <1s resume accuracy
    private var pausedPositionMs: Long = 0L
    private var pausedChunkIndex: Int = 0

    private var progressTrackingJob: Job? = null
    private var thinkingJob: Job? = null

    val voiceManager: VoiceAssistantManager = VoiceAssistantManager(
        context = context,
        onInterruptDetected = { handleInterrupt() },
        onQuestionRecognized = { question -> handleQuestion(question) },
        onResumeDetected = { handleResume() }
    )

    init {
        AudioPlayerManager.setOnCompletionListener {
            if (_currentState.value == PlayerState.PLAYING) {
                onChunkFinished()
            }
        }
    }

    fun loadBook(bookId: String, initialChunkIndex: Int = 0) {
        _currentBookId.value = bookId
        _currentChunkIndex.value = initialChunkIndex
        pausedChunkIndex = initialChunkIndex
        pausedPositionMs = 0L
        _currentState.value = PlayerState.IDLE

        val audioFile = bookRepository.getChunkAudioFile(bookId, initialChunkIndex)
        if (audioFile.exists() && audioFile.length() > 0) {
            _stateMessage.value = "第 ${initialChunkIndex + 1} 块就绪，随时可开播"
        } else {
            _stateMessage.value = "秒开流水线启动中，首块就绪后即可开播..."
        }
    }

    fun startPlayingChunk(bookId: String, chunkIndex: Int, startPositionMs: Long = 0L) {
        _currentBookId.value = bookId
        _currentChunkIndex.value = chunkIndex
        pausedChunkIndex = chunkIndex
        pausedPositionMs = startPositionMs

        scope.launch {
            val chunk = bookRepository.getChunk(bookId, chunkIndex)
            if (chunk == null) {
                _stateMessage.value = "分块数据不存在"
                return@launch
            }

            val audioFile = bookRepository.getChunkAudioFile(bookId, chunkIndex)
            if (!audioFile.exists() || audioFile.length() == 0L) {
                _stateMessage.value = "正在即时合成第 ${chunkIndex + 1} 块音频..."
                val synthesisResult = audioSynthesisManager.synthesizeChunk(chunk.textContent, audioFile)
                if (synthesisResult.isFailure) {
                    val err = synthesisResult.exceptionOrNull()?.message ?: "音频生成失败"
                    _stateMessage.value = "音频生成失败: $err"
                    AppLogger.logError(
                        category = "PLAYER",
                        tag = "InteractionStateMachine",
                        message = "Immediate synthesis failed for chunk $chunkIndex: $err"
                    )
                    return@launch
                }
                bookRepository.markChunkCompleted(
                    chunk.id,
                    bookId,
                    audioFile.absolutePath,
                    synthesisResult.getOrThrow().durationMs
                )
            }

            // Play via AudioPlayerManager (guaranteed to be ready and non-blocking)
            AudioPlayerManager.playAudioFile(audioFile.absolutePath, startPositionMs)

            _currentState.value = PlayerState.PLAYING
            _stateMessage.value = "正在朗读正文（随时说“停一下”打断）"
            startProgressTracking()

            // Start listening for wake words ("停一下", "暂停", etc.)
            voiceManager.startListeningForInterrupt()
        }
    }

    private fun startProgressTracking() {
        progressTrackingJob?.cancel()
        progressTrackingJob = scope.launch {
            while (_currentState.value == PlayerState.PLAYING) {
                val player = AudioPlayerManager.getPlayer()
                if (player != null) {
                    val pos = player.currentPosition
                    val dur = player.duration
                    if (pos >= 0) _currentPositionMs.value = pos
                    if (dur > 0) _chunkDurationMs.value = dur

                    // Check if chunk playback completed
                    if (player.playbackState == androidx.media3.common.Player.STATE_ENDED) {
                        onChunkFinished()
                        break
                    }
                }
                delay(200)
            }
        }
    }

    private fun onChunkFinished() {
        val bookId = _currentBookId.value ?: return
        val nextIndex = _currentChunkIndex.value + 1
        scope.launch {
            val nextChunk = bookRepository.getChunk(bookId, nextIndex)
            if (nextChunk != null) {
                val nextAudioFile = bookRepository.getChunkAudioFile(bookId, nextIndex)
                val isReady = nextChunk.status == ChunkEntity.STATUS_COMPLETED && nextAudioFile.exists() && nextAudioFile.length() > 0

                if (!isReady) {
                    AppLogger.logInfo("InteractionStateMachine", "Buffer exhausted: chunk $nextIndex is not ready yet. Playing voice prompt.")
                    _stateMessage.value = "已播完当前分块，正在加速缓存下一章..."
                    
                    // Voice prompt: "如果转换好的音频都播放完了，新的 chunk 还没有转换完成。要有语音提示。"
                    audioSynthesisManager.speakBufferExhaustedPrompt()
                }

                AppLogger.logInfo("InteractionStateMachine", "Advancing to next chunk $nextIndex")
                startPlayingChunk(bookId, nextIndex, 0L)
            } else {
                _currentState.value = PlayerState.IDLE
                _stateMessage.value = "全书朗读完毕"
            }
        }
    }

    /**
     * State transition: PLAYING -> LISTENING
     * Instant pause within 500ms, records precise position, sounds pleasant chime.
     */
    fun handleInterrupt() {
        if (_currentState.value != PlayerState.PLAYING) return

        progressTrackingJob?.cancel()
        val player = AudioPlayerManager.getPlayer()

        // 1. Record precise position timestamp and chunk index
        pausedPositionMs = player?.currentPosition ?: _currentPositionMs.value
        pausedChunkIndex = _currentChunkIndex.value
        AudioPlayerManager.pause()

        _currentState.value = PlayerState.LISTENING
        _stateMessage.value = "正在倾听提问...（请说话）"
        AppLogger.logInfo("InteractionStateMachine", "Interrupted at chunk $pausedChunkIndex, position ${pausedPositionMs}ms")

        // 2. Play gentle chime and start speech recognition
        scope.launch {
            AudioCueHelper.playListeningStartCue()
            voiceManager.startListeningForQuestion()
        }
    }

    /**
     * State transition: LISTENING -> THINKING -> ANSWERING
     */
    fun handleQuestion(question: String) {
        voiceManager.stopListening()
        progressTrackingJob?.cancel()
        val player = AudioPlayerManager.getPlayer()
        if (_currentState.value == PlayerState.PLAYING) {
            pausedPositionMs = player?.currentPosition ?: _currentPositionMs.value
            pausedChunkIndex = _currentChunkIndex.value
            AudioPlayerManager.pause()
        }
        _lastUserQuestion.value = question
        _currentState.value = PlayerState.THINKING
        _stateMessage.value = "伴读助手思考中..."

        thinkingJob?.cancel()
        thinkingJob = scope.launch {
            val bookId = _currentBookId.value
            val currentChunk = if (bookId != null) bookRepository.getChunk(bookId, pausedChunkIndex) else null
            val prevChunk = if (bookId != null && pausedChunkIndex > 0) bookRepository.getChunk(bookId, pausedChunkIndex - 1) else null

            val apiKey = settingsRepository.getEffectiveApiKey()
            val answerResult = GeminiClient.answerQuestion(
                apiKey = apiKey,
                prevChunkText = prevChunk?.textContent,
                currentChunkText = currentChunk?.textContent ?: "",
                userQuestion = question
            )

            val answerText = if (answerResult.isSuccess) {
                answerResult.getOrThrow()
            } else {
                val err = answerResult.exceptionOrNull()?.message ?: "未知异常"
                "抱歉，结合当前正文回答时遇到问题：$err"
            }

            _lastAiAnswer.value = answerText
            _currentState.value = PlayerState.ANSWERING
            _stateMessage.value = "伴读助手正在解答（Gemini TTS）..."

            // Actively listen for "继续" so the driver can interrupt/skip the answer at any moment
            voiceManager.startListeningForResume()

            // Speak answer to driver via Gemini TTS
            audioSynthesisManager.speakAnswer(answerText) {
                // Speech finished -> keep paused, actively listen for resume voice command
                _stateMessage.value = "解答完毕。随时说“继续”或点击【播放】继续听书"
                voiceManager.startListeningForResume()
            }
        }
    }

    /**
     * State transition: ANSWERING -> RESUMING -> PLAYING
     * Precise resume within <1 second margin!
     */
    fun handleResume() {
        voiceManager.stopListening()
        audioSynthesisManager.stopSpeaking()

        _currentState.value = PlayerState.RESUMING
        _stateMessage.value = "正在恢复正文进度..."

        scope.launch {
            AudioCueHelper.playResumingCue()
            delay(200)

            val bookId = _currentBookId.value
            if (bookId != null) {
                AppLogger.logInfo("InteractionStateMachine", "Resuming chunk $pausedChunkIndex at exact pos ${pausedPositionMs}ms")
                startPlayingChunk(bookId, pausedChunkIndex, pausedPositionMs)
            } else {
                _currentState.value = PlayerState.IDLE
            }
        }
    }

    fun pause() {
        progressTrackingJob?.cancel()
        thinkingJob?.cancel()
        audioSynthesisManager.stopSpeaking()
        val player = AudioPlayerManager.getPlayer()
        pausedPositionMs = player?.currentPosition ?: _currentPositionMs.value
        pausedChunkIndex = _currentChunkIndex.value
        AudioPlayerManager.pause()
        _currentState.value = PlayerState.IDLE
        _stateMessage.value = "播放已暂停（可随时说“继续”或点击【播放】）"

        // Keep voice recognizer listening for "继续" or "播放" even while paused
        voiceManager.startListeningForResume()
    }

    fun togglePlayPause() {
        if (_currentState.value == PlayerState.PLAYING || _currentState.value == PlayerState.ANSWERING) {
            pause()
        } else {
            val bookId = _currentBookId.value ?: return
            startPlayingChunk(bookId, pausedChunkIndex, pausedPositionMs)
        }
    }

    fun skipPreviousChunk() {
        val bookId = _currentBookId.value ?: return
        val prev = (_currentChunkIndex.value - 1).coerceAtLeast(0)
        startPlayingChunk(bookId, prev, 0L)
    }

    fun skipNextChunk() {
        val bookId = _currentBookId.value ?: return
        val next = _currentChunkIndex.value + 1
        startPlayingChunk(bookId, next, 0L)
    }

    fun seekRelative(seconds: Int) {
        val player = AudioPlayerManager.getPlayer() ?: return
        val target = (player.currentPosition + seconds * 1000L).coerceAtLeast(0L).coerceAtMost(player.duration)
        AudioPlayerManager.seekTo(target)
        _currentPositionMs.value = target
        pausedPositionMs = target
    }

    fun release() {
        progressTrackingJob?.cancel()
        thinkingJob?.cancel()
        voiceManager.destroy()
        audioSynthesisManager.release()
    }
}
