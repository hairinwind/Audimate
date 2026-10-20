package com.example.tts

import android.content.Context
import android.media.MediaMetadataRetriever
import com.example.data.repository.SettingsRepository
import com.example.logging.AppLogger
import com.example.service.AudioPlayerManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class AudioSynthesisManager(
    private val context: Context,
    private val settingsRepository: SettingsRepository
) {
    val edgeTts = EdgeTtsClient()
    private val androidTts = AndroidTtsFallback(context)

    suspend fun synthesizeChunk(
        textContent: String,
        outputFile: File
    ): Result<SynthesisResult> = withContext(Dispatchers.IO) {
        val voiceId = settingsRepository.edgeVoiceId.value
        val speed = settingsRepository.edgeSpeed.value

        var usedEngine = "Edge 微软神经语音"

        AppLogger.logInfo("AudioSynthesisManager", "Synthesizing with Edge TTS (voice: $voiceId, speed: $speed)...")
        val edgeResult = edgeTts.synthesizeToFile(
            text = textContent,
            outputFile = outputFile,
            voice = voiceId,
            speed = speed
        )

        val finalFileResult = if (edgeResult.isSuccess) {
            val voiceName = EdgeTtsConstants.AVAILABLE_VOICES.find { it.id == voiceId }?.name ?: voiceId
            usedEngine = "Edge 微软神经语音 ($voiceName)"
            edgeResult
        } else {
            usedEngine = "Android 本地离线语音"
            val errorMsg = edgeResult.exceptionOrNull()?.message ?: "Edge TTS error"
            AppLogger.logInfo(
                "AudioSynthesisManager",
                "Edge TTS failed ($errorMsg), smoothly falling back to Android native TTS"
            )
            androidTts.synthesizeToFile(textContent, outputFile)
        }

        if (finalFileResult.isSuccess) {
            val file = finalFileResult.getOrThrow()
            val durationMs = extractAudioDuration(file)
            Result.success(SynthesisResult(file, durationMs, usedEngine))
        } else {
            val ex = finalFileResult.exceptionOrNull() ?: Exception("Audio synthesis failed")
            Result.failure(ex)
        }
    }

    suspend fun speakBufferExhaustedPrompt(onDone: () -> Unit = {}) {
        speakAnswer("正在加速为您缓存下一章节，请稍候片刻...", onDone)
    }

    /**
     * Synthesizes and plays companion QA answers or system prompts using Edge neural TTS.
     */
    suspend fun speakAnswer(text: String, onDone: () -> Unit = {}) = withContext(Dispatchers.IO) {
        val voiceId = settingsRepository.edgeVoiceId.value
        val speed = settingsRepository.edgeSpeed.value
        val answerFile = File(context.cacheDir, "edge_qa_answer_${System.currentTimeMillis()}.mp3")

        var edgeSuccess = false
        val edgeResult = edgeTts.synthesizeToFile(
            text = text,
            outputFile = answerFile,
            voice = voiceId,
            speed = speed
        )

        if (edgeResult.isSuccess && answerFile.exists() && answerFile.length() > 0) {
            edgeSuccess = true
            AppLogger.logInfo(
                "AudioSynthesisManager",
                "Edge TTS succeeded for QA answer (${answerFile.length()} bytes). Playing via audio player."
            )
            withContext(Dispatchers.Main) {
                AudioPlayerManager.playAudioFile(answerFile.absolutePath, 0L)
                AudioPlayerManager.setOnCompletionListener {
                    try {
                        if (answerFile.exists()) answerFile.delete()
                    } catch (e: Exception) {
                        // ignore cleanup error
                    }
                    onDone()
                }
            }
        }

        if (!edgeSuccess) {
            AppLogger.logInfo("AudioSynthesisManager", "Edge TTS failed for QA, using fallback speech engine")
            withContext(Dispatchers.Main) {
                androidTts.speakDirect(text, onDone)
            }
        }
    }

    fun stopSpeaking() {
        AudioPlayerManager.stop()
        androidTts.stop()
    }

    private fun extractAudioDuration(file: File): Long {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(file.absolutePath)
            val durationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            retriever.release()
            durationStr?.toLongOrNull() ?: 15000L
        } catch (e: Exception) {
            15000L
        }
    }

    fun release() {
        androidTts.release()
    }
}

data class SynthesisResult(
    val file: File,
    val durationMs: Long,
    val engineUsed: String
)
