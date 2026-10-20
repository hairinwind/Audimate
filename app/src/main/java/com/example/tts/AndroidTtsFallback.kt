package com.example.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.example.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.Locale
import java.util.UUID

class AndroidTtsFallback(private val context: Context) {
    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val initDeferred = CompletableDeferred<Boolean>()

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    tts?.setLanguage(Locale.getDefault())
                }
                isInitialized = true
                initDeferred.complete(true)
            } else {
                AppLogger.logInfo(
                    tag = "AndroidTtsFallback",
                    message = "System TextToSpeech init status: $status"
                )
                initDeferred.complete(false)
            }
        }
    }

    suspend fun synthesizeToFile(text: String, outputFile: File): Result<File> = withContext(Dispatchers.IO) {
        if (!isInitialized) {
            val ready = withTimeoutOrNull(3000) { initDeferred.await() } ?: false
            if (!ready) {
                val errorMsg = "System TextToSpeech engine timed out during initialization"
                AppLogger.logInfo("AndroidTtsFallback", errorMsg)
                return@withContext Result.failure(IllegalStateException(errorMsg))
            }
        }

        val engine = tts ?: return@withContext Result.failure(IllegalStateException("TextToSpeech is null"))

        val utteranceId = UUID.randomUUID().toString()
        val deferredCompletion = CompletableDeferred<Boolean>()

        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    deferredCompletion.complete(true)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    deferredCompletion.complete(false)
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    AppLogger.logInfo(
                        tag = "AndroidTtsFallback",
                        message = "System TTS synthesis error code: $errorCode"
                    )
                    deferredCompletion.complete(false)
                }
            }
        })

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val params = Bundle()
        val status = engine.synthesizeToFile(text, params, outputFile, utteranceId)
        if (status != TextToSpeech.SUCCESS) {
            val errorMsg = "TextToSpeech.synthesizeToFile returned failure code: $status"
            AppLogger.logInfo("AndroidTtsFallback", errorMsg)
            return@withContext Result.failure(Exception(errorMsg))
        }

        val completed = withTimeoutOrNull(6000) { deferredCompletion.await() } ?: false
        if (completed && outputFile.exists() && outputFile.length() > 0) {
            AppLogger.logInfo("AndroidTtsFallback", "Fallback TTS synthesized ${outputFile.name} (${outputFile.length()} bytes)")
            Result.success(outputFile)
        } else {
            val errorMsg = "Android 系统本地 TTS 未生成有效音频（已优先使用 Edge 微软神经网络语音）"
            AppLogger.logInfo("AndroidTtsFallback", errorMsg)
            Result.failure(Exception(errorMsg))
        }
    }

    fun speakDirect(text: String, onDone: () -> Unit = {}) {
        val utteranceId = UUID.randomUUID().toString()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {}
            override fun onDone(id: String?) {
                if (id == utteranceId) onDone()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(id: String?) {
                if (id == utteranceId) onDone()
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
    }

    fun stop() {
        tts?.stop()
    }

    fun release() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (e: Exception) {
            // Ignore
        }
    }
}
