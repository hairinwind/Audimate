package com.example.statemachine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.sin

object AudioCueHelper {
    // Generate a gentle pleasant two-tone chime for start of listening
    suspend fun playListeningStartCue() = withContext(Dispatchers.IO) {
        try {
            playToneSequence(listOf(523.25 to 120, 659.25 to 160)) // C5 -> E5 gentle chime
        } catch (e: Exception) {
            // Ignore if audio device is unavailable
        }
    }

    // Generate a crisp acknowledgment cue when user question is received and thinking starts
    suspend fun playQuestionReceivedCue() = withContext(Dispatchers.IO) {
        try {
            playToneSequence(listOf(659.25 to 80, 880.0 to 120)) // E5 -> A5
        } catch (e: Exception) {
            // Ignore
        }
    }

    // Generate a soft confirmation cue when resuming playback
    suspend fun playResumingCue() = withContext(Dispatchers.IO) {
        try {
            playToneSequence(listOf(659.25 to 100, 783.99 to 150)) // E5 -> G5
        } catch (e: Exception) {
            // Ignore
        }
    }

    private fun playToneSequence(tones: List<Pair<Double, Int>>) {
        val sampleRate = 44100
        val totalMs = tones.sumOf { it.second }
        val numSamples = (sampleRate * totalMs) / 1000
        val buffer = ShortArray(numSamples)

        var sampleIndex = 0
        for ((freq, durationMs) in tones) {
            val toneSamples = (sampleRate * durationMs) / 1000
            for (i in 0 until toneSamples) {
                if (sampleIndex >= numSamples) break
                // Apply a smooth cosine window to avoid clicks
                val envelope = 0.5 * (1.0 - kotlin.math.cos(2.0 * Math.PI * i / toneSamples))
                val angle = 2.0 * Math.PI * i / (sampleRate / freq)
                val sampleValue = (sin(angle) * 16384 * envelope).toInt().toShort()
                buffer[sampleIndex++] = sampleValue
            }
        }

        val audioTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
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
        Thread.sleep(totalMs.toLong() + 50)
        audioTrack.release()
    }
}
