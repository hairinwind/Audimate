package com.example.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SettingsRepository(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("drive_reader_prefs", Context.MODE_PRIVATE)

    private val _apiKey = MutableStateFlow(getSavedApiKey())
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    private val _ttsEngine = MutableStateFlow(prefs.getString(KEY_TTS_ENGINE, "edge") ?: "edge")
    val ttsEngine: StateFlow<String> = _ttsEngine.asStateFlow()

    private val _edgeVoiceId = MutableStateFlow(prefs.getString(KEY_EDGE_VOICE_ID, "zh-CN-YunxiNeural") ?: "zh-CN-YunxiNeural")
    val edgeVoiceId: StateFlow<String> = _edgeVoiceId.asStateFlow()

    private val _edgeSpeed = MutableStateFlow(prefs.getFloat(KEY_EDGE_SPEED, 1.0f))
    val edgeSpeed: StateFlow<Float> = _edgeSpeed.asStateFlow()

    private val _ttsVoice = MutableStateFlow(prefs.getString(KEY_TTS_VOICE, "zh-CN-YunxiNeural") ?: "zh-CN-YunxiNeural")
    val ttsVoice: StateFlow<String> = _ttsVoice.asStateFlow()

    private val _kokoroSpeakerId = MutableStateFlow(prefs.getInt(KEY_KOKORO_SPEAKER_ID, 18))
    val kokoroSpeakerId: StateFlow<Int> = _kokoroSpeakerId.asStateFlow()

    private val _kokoroSpeed = MutableStateFlow(prefs.getFloat(KEY_KOKORO_SPEED, 1.0f))
    val kokoroSpeed: StateFlow<Float> = _kokoroSpeed.asStateFlow()

    private val _concurrency = MutableStateFlow(prefs.getInt(KEY_CONCURRENCY, 3))
    val concurrency: StateFlow<Int> = _concurrency.asStateFlow()

    private val _autoResumeSeconds = MutableStateFlow(prefs.getInt(KEY_AUTO_RESUME_SEC, 0))
    val autoResumeSeconds: StateFlow<Int> = _autoResumeSeconds.asStateFlow()

    private val _fallbackToNativeTts = MutableStateFlow(prefs.getBoolean(KEY_FALLBACK_NATIVE_TTS, false))
    val fallbackToNativeTts: StateFlow<Boolean> = _fallbackToNativeTts.asStateFlow()

    private val _prefetchAheadCount = MutableStateFlow(prefs.getInt(KEY_PREFETCH_AHEAD, 5))
    val prefetchAheadCount: StateFlow<Int> = _prefetchAheadCount.asStateFlow()

    fun getEffectiveApiKey(): String {
        val customKey = prefs.getString(KEY_API_KEY, "")?.trim()
        if (!customKey.isNullOrEmpty()) {
            return customKey
        }
        val buildConfigKey = try {
            BuildConfig.GEMINI_API_KEY.trim()
        } catch (e: Exception) {
            ""
        }
        if (buildConfigKey.isNotEmpty() && !buildConfigKey.startsWith("MY_GEMINI")) {
            return buildConfigKey
        }
        val envKey = try {
            System.getenv("GEMINI_API_KEY")?.trim() ?: ""
        } catch (e: Exception) {
            ""
        }
        if (envKey.isNotEmpty()) {
            return envKey
        }
        return ""
    }

    private fun getSavedApiKey(): String {
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(key: String) {
        prefs.edit().putString(KEY_API_KEY, key.trim()).apply()
        _apiKey.value = key.trim()
    }

    fun setTtsEngine(engine: String) {
        prefs.edit().putString(KEY_TTS_ENGINE, engine).apply()
        _ttsEngine.value = engine
    }

    fun setEdgeVoiceId(voiceId: String) {
        prefs.edit().putString(KEY_EDGE_VOICE_ID, voiceId).apply()
        _edgeVoiceId.value = voiceId
    }

    fun setEdgeSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat(KEY_EDGE_SPEED, clamped).apply()
        _edgeSpeed.value = clamped
    }

    fun setTtsVoice(voice: String) {
        prefs.edit().putString(KEY_TTS_VOICE, voice).apply()
        _ttsVoice.value = voice
    }

    fun setKokoroSpeakerId(sid: Int) {
        prefs.edit().putInt(KEY_KOKORO_SPEAKER_ID, sid).apply()
        _kokoroSpeakerId.value = sid
    }

    fun setKokoroSpeed(speed: Float) {
        val clamped = speed.coerceIn(0.5f, 2.0f)
        prefs.edit().putFloat(KEY_KOKORO_SPEED, clamped).apply()
        _kokoroSpeed.value = clamped
    }

    fun setConcurrency(workers: Int) {
        val clamped = workers.coerceIn(1, 5)
        prefs.edit().putInt(KEY_CONCURRENCY, clamped).apply()
        _concurrency.value = clamped
    }

    fun setFallbackToNativeTts(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_FALLBACK_NATIVE_TTS, enabled).apply()
        _fallbackToNativeTts.value = enabled
    }

    fun setPrefetchAheadCount(count: Int) {
        val clamped = count.coerceIn(2, 50)
        prefs.edit().putInt(KEY_PREFETCH_AHEAD, clamped).apply()
        _prefetchAheadCount.value = clamped
    }

    companion object {
        private const val KEY_API_KEY = "custom_gemini_api_key"
        private const val KEY_TTS_ENGINE = "tts_engine_type"
        private const val KEY_EDGE_VOICE_ID = "edge_voice_id"
        private const val KEY_EDGE_SPEED = "edge_speed"
        private const val KEY_TTS_VOICE = "tts_voice_name"
        private const val KEY_KOKORO_SPEAKER_ID = "kokoro_speaker_id"
        private const val KEY_KOKORO_SPEED = "kokoro_speed"
        private const val KEY_CONCURRENCY = "prefetch_concurrency"
        private const val KEY_AUTO_RESUME_SEC = "auto_resume_sec"
        private const val KEY_FALLBACK_NATIVE_TTS = "fallback_native_tts"
        private const val KEY_PREFETCH_AHEAD = "prefetch_ahead_count"
    }
}
