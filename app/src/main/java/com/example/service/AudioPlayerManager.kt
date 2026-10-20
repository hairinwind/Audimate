package com.example.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.sin

object AudioPlayerManager {
    private var appContext: Context? = null
    private var exoPlayer: ExoPlayer? = null

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    private val _systemVolumePercent = MutableStateFlow(100)
    val systemVolumePercent: StateFlow<Int> = _systemVolumePercent.asStateFlow()

    private var onCompletionListener: (() -> Unit)? = null

    @Synchronized
    fun init(context: Context): ExoPlayer {
        val app = context.applicationContext
        appContext = app

        exoPlayer?.let { return it }

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val player = ExoPlayer.Builder(app)
            .setAudioAttributes(audioAttributes, false) // Set handleAudioFocus = false so virtual emulator doesn't silence audio
            .build().apply {
                volume = 1.0f
                playWhenReady = true
            }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                AppLogger.logInfo(
                    "AudioPlayerManager",
                    "PlaybackState: $playbackState (BUFFERING=${Player.STATE_BUFFERING}, READY=${Player.STATE_READY}, ENDED=${Player.STATE_ENDED}, IDLE=${Player.STATE_IDLE})"
                )
                if (playbackState == Player.STATE_ENDED) {
                    onCompletionListener?.invoke()
                }
                AudiobookMediaService.currentServiceInstance?.updateNotification()
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _isPlaying.value = isPlaying
                AppLogger.logInfo("AudioPlayerManager", "isPlayling changed: $isPlaying, volume=${player.volume}")
                AudiobookMediaService.currentServiceInstance?.updateNotification()
            }

            override fun onPlayerError(error: PlaybackException) {
                val errorMsg = "ExoPlayer error [${error.errorCodeName} / code ${error.errorCode}]: ${error.message}"
                _lastError.value = errorMsg
                AppLogger.logError(
                    category = "PLAYER",
                    tag = "AudioPlayerManager",
                    message = errorMsg,
                    throwable = error
                )
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                AppLogger.logInfo("AudioPlayerManager", "Playback suppression reason changed: $playbackSuppressionReason")
            }
        })

        ensureDeviceAudible(app)

        exoPlayer = player
        AppLogger.logInfo("AudioPlayerManager", "ExoPlayer initialized successfully")
        return player
    }

    fun getPlayer(): ExoPlayer? = exoPlayer

    fun setOnCompletionListener(listener: (() -> Unit)?) {
        this.onCompletionListener = listener
    }

    fun ensureDeviceAudible(context: Context? = appContext) {
        try {
            val ctx = context ?: return
            val audioManager = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val currentVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            val isMuted = audioManager.isStreamMute(AudioManager.STREAM_MUSIC)

            if (currentVol == 0 || isMuted) {
                val target = (maxVol * 0.9).toInt().coerceAtLeast(1)
                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
                _systemVolumePercent.value = ((target.toFloat() / maxVol) * 100).toInt()
                AppLogger.logInfo("AudioPlayerManager", "Unmuted and raised STREAM_MUSIC volume to $target / $maxVol")
            } else {
                _systemVolumePercent.value = ((currentVol.toFloat() / maxVol) * 100).toInt()
            }
        } catch (e: Exception) {
            AppLogger.logError("PLAYER", "AudioPlayerManager", "Failed to adjust volume: ${e.message}", e)
        }
    }

    fun playAudioFile(filePath: String, startPositionMs: Long = 0L) {
        val player = exoPlayer ?: appContext?.let { init(it) } ?: run {
            AppLogger.logError("PLAYER", "AudioPlayerManager", "ExoPlayer is null and cannot be initialized")
            return
        }

        try {
            val file = File(filePath)
            if (!file.exists() || file.length() == 0L) {
                val err = "Audio file does not exist or is empty (${file.length()} bytes): $filePath"
                _lastError.value = err
                AppLogger.logError("PLAYER", "AudioPlayerManager", err)
                return
            }

            ensureDeviceAudible()
            player.volume = 1.0f

            // CRITICAL: Must use file:// URI schema via Uri.fromFile so Media3 FileDataSource opens it properly
            val fileUri = Uri.fromFile(file)
            val mediaItem = MediaItem.fromUri(fileUri)

            player.setMediaItem(mediaItem, startPositionMs)
            player.prepare()
            player.play()

            AppLogger.logInfo(
                "AudioPlayerManager",
                "Playing file: ${file.name} (${file.length()} bytes) at ${startPositionMs}ms via URI: $fileUri"
            )
            AudiobookMediaService.currentServiceInstance?.updateNotification()
        } catch (e: Exception) {
            val err = "Failed to play audio file: ${e.message}"
            _lastError.value = err
            AppLogger.logError(
                category = "PLAYER",
                tag = "AudioPlayerManager",
                message = err,
                throwable = e,
                extraInfo = "FilePath: $filePath"
            )
        }
    }

    fun pause() {
        exoPlayer?.pause()
        AudiobookMediaService.currentServiceInstance?.updateNotification()
    }

    fun stop() {
        exoPlayer?.stop()
        AudiobookMediaService.currentServiceInstance?.updateNotification()
    }

    fun resume() {
        ensureDeviceAudible()
        exoPlayer?.volume = 1.0f
        exoPlayer?.play()
        AudiobookMediaService.currentServiceInstance?.updateNotification()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    /**
     * Plays a high-fidelity human speech test ("你好，我是车载伴读助手")
     * using Gemini TTS pre-rendered audio, allowing immediate verification
     * that human speech playback is crystal clear and not distorted.
     */
    fun playTestChime(onDone: () -> Unit = {}) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                ensureDeviceAudible()
                val ctx = appContext
                if (ctx != null) {
                    val speechFile = File(ctx.filesDir, "welcome_speech.wav")
                    if (!speechFile.exists() || speechFile.length() == 0L) {
                        val resId = ctx.resources.getIdentifier("welcome_speech", "raw", ctx.packageName)
                        if (resId != 0) {
                            ctx.resources.openRawResource(resId).use { input ->
                                java.io.FileOutputStream(speechFile).use { output ->
                                    input.copyTo(output)
                                }
                            }
                        }
                    }

                    if (speechFile.exists() && speechFile.length() > 0) {
                        playAudioFile(speechFile.absolutePath)
                        onDone()
                        return@launch
                    }
                }

                // Fallback gentle pleasant dual-harmonic tone (C5 + E5)
                val sampleRate = 44100
                val durationMs = 500
                val numSamples = (sampleRate * durationMs) / 1000
                val buffer = ShortArray(numSamples)

                for (i in 0 until numSamples) {
                    val angle1 = 2.0 * Math.PI * i / (sampleRate / 523.25)
                    val angle2 = 2.0 * Math.PI * i / (sampleRate / 659.25)
                    val envelope = (1.0 - (i.toDouble() / numSamples))
                    buffer[i] = (((sin(angle1) + sin(angle2)) * 0.5) * 20000 * envelope).toInt().toShort()
                }

                val audioTrack = AudioTrack.Builder()
                    .setAudioAttributes(
                        android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(sampleRate)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setBufferSizeInBytes(buffer.size * 2)
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .build()

                audioTrack.write(buffer, 0, buffer.size)
                audioTrack.play()
                Thread.sleep(durationMs.toLong() + 50)
                audioTrack.release()
                onDone()
            } catch (e: Exception) {
                AppLogger.logError("PLAYER", "AudioPlayerManager", "Failed to play test speech: ${e.message}", e)
                onDone()
            }
        }
    }

    fun release() {
        try {
            exoPlayer?.release()
            exoPlayer = null
        } catch (e: Exception) {
            // Ignore
        }
    }
}
