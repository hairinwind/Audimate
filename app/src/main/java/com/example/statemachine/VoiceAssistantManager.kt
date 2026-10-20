package com.example.statemachine

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.example.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

class VoiceAssistantManager(
    private val context: Context,
    private val onInterruptDetected: () -> Unit,
    private val onQuestionRecognized: (String) -> Unit,
    private val onResumeDetected: () -> Unit
) {
    private val scope = CoroutineScope(Dispatchers.Main)
    private var speechRecognizer: SpeechRecognizer? = null
    private var isListeningForInterrupt = false
    private var isListeningForQuestion = false
    private var isListeningForResume = false

    private var silenceWatchdogJob: Job? = null
    private var questionFinishDebounceJob: Job? = null
    private var initialSilenceJob: Job? = null
    private var maxQuestionDurationJob: Job? = null
    private var endOfSpeechPendingJob: Job? = null
    private var restartJob: Job? = null
    private var hasDetectedSpeechInQuestion = false
    private var consecutiveErrors = 0

    private val _isMicActive = MutableStateFlow(false)
    val isMicActive: StateFlow<Boolean> = _isMicActive.asStateFlow()

    private val _isListeningForQuestionFlow = MutableStateFlow(false)
    val isListeningForQuestionFlow: StateFlow<Boolean> = _isListeningForQuestionFlow.asStateFlow()

    private val _recognizedPartialText = MutableStateFlow("")
    val recognizedPartialText: StateFlow<String> = _recognizedPartialText.asStateFlow()

    private val _questionSilencePrompt = MutableStateFlow("")
    val questionSilencePrompt: StateFlow<String> = _questionSilencePrompt.asStateFlow()

    private val _audioRmsDb = MutableStateFlow(0f)
    val audioRmsDb: StateFlow<Float> = _audioRmsDb.asStateFlow()

    init {
        initSpeechRecognizer()
    }

    fun onPermissionGranted() {
        scope.launch {
            initSpeechRecognizer()
            if (isListeningForInterrupt || isListeningForResume || isListeningForQuestion) {
                startInternalRecognizer()
            }
        }
    }

    private fun initSpeechRecognizer() {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            AppLogger.logInfo("VoiceAssistantManager", "SpeechRecognizer not natively available on this device; simulation & UI modes ready")
            return
        }

        try {
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                setRecognitionListener(createListener())
            }
            AppLogger.logInfo("VoiceAssistantManager", "SpeechRecognizer successfully initialized")
        } catch (e: Exception) {
            AppLogger.logError(
                category = "STT",
                tag = "VoiceAssistantManager",
                message = "Failed to initialize SpeechRecognizer: ${e.message}",
                throwable = e
            )
        }
    }

    private fun createListener(): RecognitionListener {
        return object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                _isMicActive.value = true
                consecutiveErrors = 0
            }

            override fun onBeginningOfSpeech() {
                if (isListeningForQuestion) {
                    hasDetectedSpeechInQuestion = true
                    initialSilenceJob?.cancel()
                    _questionSilencePrompt.value = "正在聆听您的提问..."
                }
            }

            override fun onRmsChanged(rmsdB: Float) {
                _audioRmsDb.value = rmsdB
            }

            override fun onBufferReceived(buffer: ByteArray?) {}

            override fun onEndOfSpeech() {
                _isMicActive.value = false
                if (isListeningForQuestion && _recognizedPartialText.value.isNotBlank()) {
                    _questionSilencePrompt.value = "检测到说话结束，即将解答..."
                    // Android engine detected speech boundary. Wait 400ms for onResults; if not delivered, finalize immediately!
                    endOfSpeechPendingJob?.cancel()
                    endOfSpeechPendingJob = scope.launch {
                        delay(400)
                        if (isListeningForQuestion && _recognizedPartialText.value.isNotBlank()) {
                            AppLogger.logInfo("VoiceAssistantManager", "Finalizing on onEndOfSpeech event: ${_recognizedPartialText.value}")
                            finalizeQuestion(_recognizedPartialText.value)
                        }
                    }
                }
            }

            override fun onError(error: Int) {
                _isMicActive.value = false
                consecutiveErrors++
                AppLogger.logDebug("VoiceAssistantManager", "SpeechRecognizer onError: $error (consecutive: $consecutiveErrors)")

                if (isListeningForQuestion) {
                    val captured = _recognizedPartialText.value.trim()
                    if (captured.isNotBlank()) {
                        AppLogger.logInfo("VoiceAssistantManager", "STT error/timeout but has captured speech: $captured")
                        finalizeQuestion(captured)
                        return
                    } else if (error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_NO_MATCH) {
                        AppLogger.logInfo("VoiceAssistantManager", "STT no match or timeout without speech")
                        finalizeQuestion("请简要解释当前段落的背景与重点")
                        return
                    }
                }

                // Seamlessly restart continuous keyword recognition for interrupt / resume
                restartJob?.cancel()
                restartJob = scope.launch {
                    try {
                        speechRecognizer?.cancel()
                    } catch (e: Exception) {
                        // ignore
                    }

                    if (consecutiveErrors >= 3 || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
                        delay(500)
                        initSpeechRecognizer()
                        consecutiveErrors = 0
                    } else {
                        delay(250)
                    }

                    if (isListeningForInterrupt || isListeningForResume) {
                        startInternalRecognizer()
                    }
                }
            }

            override fun onResults(results: Bundle?) {
                _isMicActive.value = false
                consecutiveErrors = 0
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val spoken = matches?.firstOrNull()?.trim() ?: ""
                val best = if (spoken.isNotBlank()) spoken else _recognizedPartialText.value.trim()
                if (best.isNotBlank()) {
                    _recognizedPartialText.value = best
                }
                handleRecognizedSpeech(best)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val partial = matches?.firstOrNull()?.trim() ?: ""
                if (partial.isNotBlank()) {
                    _recognizedPartialText.value = partial
                }

                // Fast keyword detection in partial stream for instant <500ms reaction!
                if (isListeningForInterrupt && containsInterruptKeyword(partial)) {
                    isListeningForInterrupt = false
                    stopListening()
                    onInterruptDetected()
                } else if (isListeningForResume && containsResumeKeyword(partial)) {
                    isListeningForResume = false
                    stopListening()
                    onResumeDetected()
                } else if (isListeningForQuestion) {
                    onSpeechReceivedDuringQuestion(partial)
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        }
    }

    private fun looksLikeCompleteQuestion(text: String): Boolean {
        val trimmed = text.trim()
        if (trimmed.length < 2) return false
        val questionParticles = listOf(
            "吗", "呢", "吧", "呀", "嘛", "么", "？", "?",
            "是谁", "是什么", "为什么", "怎么回事", "啥意思", "什么意思",
            "怎么样", "如何", "哪一个", "哪些", "怎么看", "怎么做"
        )
        return questionParticles.any { trimmed.endsWith(it) || (trimmed.contains(it) && trimmed.length >= 4) }
    }

    private fun containsExplicitFinishCommand(text: String): Boolean {
        val finishCommands = listOf(
            "我说完了", "我问完了", "问完了", "说完了", "回答我", "解答一下",
            "回答一下", "就这些", "就这个", "解答吧", "回答吧"
        )
        return finishCommands.any { text.contains(it) }
    }

    private fun cleanQuestionText(text: String): String {
        var result = text.trim()
        val trailingRemovals = listOf(
            "我说完了", "我问完了", "问完了", "说完了", "回答我", "解答一下",
            "回答一下", "就这些", "就这个", "解答吧", "回答吧"
        )
        for (keyword in trailingRemovals) {
            if (result.endsWith(keyword)) {
                result = result.removeSuffix(keyword).trim()
            }
        }
        return if (result.isNotBlank()) result else text.trim()
    }

    /**
     * 判断准则 (Criteria for determining if user has finished speaking):
     * 1. 明确结束词触发：如果说出“我说完了”、“回答我”、“就这些”，立即提交（0ms延迟）
     * 2. 语气助词/疑问词 + 停顿判定：如果句尾为“吗/呢/吧/？/是什么/为什么”，只需停顿 1.0 秒即判定提问完毕
     * 3. 一般陈述式提问判定：停顿 1.5 秒即自动判定提问完毕
     * 4. 实时动态状态提示：在 UI 与状态机上清晰显示判定倒计时与状态，避免一直傻等
     */
    private fun onSpeechReceivedDuringQuestion(text: String) {
        if (!isListeningForQuestion) return
        val current = text.trim()
        if (current.isBlank()) return

        _recognizedPartialText.value = current
        hasDetectedSpeechInQuestion = true
        initialSilenceJob?.cancel()

        // 1. Explicit finish command -> immediate submit!
        if (containsExplicitFinishCommand(current)) {
            AppLogger.logInfo("VoiceAssistantManager", "Explicit question finish keyword detected in: $current")
            finalizeQuestion(cleanQuestionText(current))
            return
        }

        // 2. Schedule silence pause debounce based on question syntax
        val isLikelyFinished = looksLikeCompleteQuestion(current)
        val debounceTimeMs = if (isLikelyFinished) 1000L else 1500L

        _questionSilencePrompt.value = if (isLikelyFinished) "疑问句停顿判定中..." else "停顿判定中..."

        questionFinishDebounceJob?.cancel()
        questionFinishDebounceJob = scope.launch {
            delay(debounceTimeMs)
            if (isListeningForQuestion && _recognizedPartialText.value.isNotBlank()) {
                AppLogger.logInfo(
                    "VoiceAssistantManager",
                    "Question completed by silence detection (${debounceTimeMs}ms pause, isLikelyFinished=$isLikelyFinished): ${_recognizedPartialText.value}"
                )
                finalizeQuestion(cleanQuestionText(_recognizedPartialText.value))
            }
        }
    }

    /**
     * Finalize user question and trigger thinking & answering transition
     */
    fun finalizeQuestion(question: String) {
        if (!isListeningForQuestion) return
        isListeningForQuestion = false
        _isListeningForQuestionFlow.value = false

        questionFinishDebounceJob?.cancel()
        maxQuestionDurationJob?.cancel()
        initialSilenceJob?.cancel()
        endOfSpeechPendingJob?.cancel()
        stopListening()

        val finalQuestion = question.trim().ifBlank { "请简要解释当前段落的背景与重点" }
        _recognizedPartialText.value = finalQuestion
        _questionSilencePrompt.value = "已完成提问识别"
        AppLogger.logInfo("VoiceAssistantManager", "Finalized user question: $finalQuestion")

        scope.launch {
            // Play crisp acknowledgment cue
            AudioCueHelper.playQuestionReceivedCue()
            onQuestionRecognized(finalQuestion)
        }
    }

    private fun handleRecognizedSpeech(text: String) {
        val lower = text.lowercase().trim()
        when {
            isListeningForInterrupt -> {
                if (containsInterruptKeyword(lower)) {
                    isListeningForInterrupt = false
                    stopListening()
                    onInterruptDetected()
                } else {
                    // Continue listening for interrupt keywords seamlessly
                    scope.launch {
                        delay(200)
                        if (isListeningForInterrupt) {
                            try { speechRecognizer?.cancel() } catch (e: Exception) {}
                            startInternalRecognizer()
                        }
                    }
                }
            }
            isListeningForQuestion -> {
                finalizeQuestion(text.ifBlank { _recognizedPartialText.value })
            }
            isListeningForResume -> {
                if (containsResumeKeyword(lower)) {
                    isListeningForResume = false
                    stopListening()
                    onResumeDetected()
                } else {
                    // Continue listening for resume keywords
                    scope.launch {
                        delay(200)
                        if (isListeningForResume) {
                            try { speechRecognizer?.cancel() } catch (e: Exception) {}
                            startInternalRecognizer()
                        }
                    }
                }
            }
        }
    }

    private fun containsInterruptKeyword(text: String): Boolean {
        val keywords = listOf(
            "停一下", "停", "暂停", "等一下", "等会", "等下", "停一停",
            "问个问题", "打断一下", "打断", "请问", "问一下", "别读了", "别放了",
            "stop", "pause", "wait"
        )
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    private fun containsResumeKeyword(text: String): Boolean {
        val keywords = listOf(
            "继续", "接着放", "继续播放", "接着读", "继续读", "播放", "放吧", "好了", "行了", "播吧",
            "继续听", "接着听", "开始播放", "接着来", "resume", "play", "continue"
        )
        return keywords.any { text.contains(it, ignoreCase = true) }
    }

    fun startListeningForInterrupt() {
        isListeningForInterrupt = true
        isListeningForQuestion = false
        isListeningForResume = false
        startInternalRecognizer()
    }

    fun startListeningForQuestion() {
        isListeningForInterrupt = false
        isListeningForQuestion = true
        _isListeningForQuestionFlow.value = true
        isListeningForResume = false
        _recognizedPartialText.value = ""
        _questionSilencePrompt.value = "请说话提问，说完停顿1.5秒自动解答..."
        hasDetectedSpeechInQuestion = false
        startInternalRecognizer()

        // 1. Initial silence timeout: if user doesn't speak at all within 6 seconds
        initialSilenceJob?.cancel()
        initialSilenceJob = scope.launch {
            delay(6000)
            if (isListeningForQuestion && !hasDetectedSpeechInQuestion) {
                val currentText = _recognizedPartialText.value.trim()
                AppLogger.logInfo("VoiceAssistantManager", "Initial silence timeout (6s)")
                finalizeQuestion(currentText.ifBlank { "请简要解释当前段落的背景与重点" })
            }
        }

        // 2. Max question duration safeguard: 12 seconds max to prevent cabin noise hanging
        maxQuestionDurationJob?.cancel()
        maxQuestionDurationJob = scope.launch {
            delay(12000)
            if (isListeningForQuestion) {
                val currentText = _recognizedPartialText.value.trim()
                AppLogger.logInfo("VoiceAssistantManager", "Max question duration timeout (12s)")
                finalizeQuestion(currentText.ifBlank { "请简要解释当前段落的背景与重点" })
            }
        }
    }

    fun startListeningForResume() {
        isListeningForInterrupt = false
        isListeningForQuestion = false
        _isListeningForQuestionFlow.value = false
        isListeningForResume = true
        startInternalRecognizer()
    }

    private fun startInternalRecognizer() {
        try {
            if (speechRecognizer == null) {
                initSpeechRecognizer()
            }
            try {
                speechRecognizer?.cancel()
            } catch (e: Exception) {
                // ignore
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            }
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            AppLogger.logDebug("VoiceAssistantManager", "startInternalRecognizer exception: ${e.message}")
        }
    }

    fun stopListening() {
        initialSilenceJob?.cancel()
        maxQuestionDurationJob?.cancel()
        questionFinishDebounceJob?.cancel()
        endOfSpeechPendingJob?.cancel()
        silenceWatchdogJob?.cancel()
        restartJob?.cancel()
        _isMicActive.value = false
        _isListeningForQuestionFlow.value = false
        try {
            speechRecognizer?.stopListening()
            speechRecognizer?.cancel()
        } catch (e: Exception) {
            // Ignore
        }
    }

    // Direct simulation helpers for driving testing & non-mic environments
    fun simulateInterrupt() {
        isListeningForInterrupt = false
        stopListening()
        onInterruptDetected()
    }

    fun simulateQuestion(question: String) {
        if (isListeningForQuestion) {
            finalizeQuestion(question)
        } else {
            onQuestionRecognized(question)
        }
    }

    fun simulateResume() {
        isListeningForResume = false
        stopListening()
        onResumeDetected()
    }

    fun destroy() {
        stopListening()
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            // Ignore
        }
        speechRecognizer = null
    }
}
