package com.example.gemini

import android.util.Base64
import com.example.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

object GeminiClient {
    private const val BASE_URL = "https://generativelanguage.googleapis.com"
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    private val okHttpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(20, TimeUnit.SECONDS)
            .build()
    }

    private val TTS_MODELS = listOf(
        "gemini-3.1-flash-tts-preview",
        "gemini-2.5-flash-preview-tts",
        "gemini-2.5-pro-preview-tts"
    )

    /**
     * In-car companion question answering.
     * System prompt & context strictly follow Section 3 of PRD.
     */
    suspend fun answerQuestion(
        apiKey: String,
        prevChunkText: String?,
        currentChunkText: String,
        userQuestion: String
    ): Result<String> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            val errorMsg = "Gemini API Key 未配置，请在右上角设置中填写您的 Gemini API Key"
            AppLogger.logError(
                category = "GEMINI_QA",
                tag = "GeminiClient",
                message = errorMsg
            )
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        try {
            val systemPrompt = """
你是一位车载随身听书伴读助手。用户正在驾车收听书籍，并打断了播放提出疑问。
你的回答必须遵循以下约束：
1. 语言高度口语化、亲切自然，单次回答控制在 1～3 句话内。
2. 表达必须完整清晰，有头有尾，结尾必须有句号，严禁半途截断或留悬念。
3. 答案纯粹面向“听觉”，严禁包含 Markdown 列表、表格、复杂符号或书面排版标记。
4. 紧密结合提供的当前书籍背景段落进行针对性解释。
""".trimIndent()

            val contextQuery = buildString {
                append("【当前收听正文上下文】：\n")
                if (!prevChunkText.isNullOrBlank()) {
                    append("[前序段落]：").append(prevChunkText.take(500)).append("\n")
                }
                append("[当前段落]：").append(currentChunkText.take(800)).append("\n\n")
                append("【用户提问】：\n").append(userQuestion)
            }

            val requestJson = JSONObject().apply {
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", systemPrompt) })
                    })
                })
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply { put("text", contextQuery) })
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.6)
                    put("maxOutputTokens", 1024)
                })
            }

            val url = "$BASE_URL/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: ""

            if (!response.isSuccessful) {
                val errorMsg = "Gemini QA 请求失败 [HTTP ${response.code}]: $responseBody"
                AppLogger.logError(
                    category = "GEMINI_QA",
                    tag = "GeminiClient",
                    message = errorMsg,
                    extraInfo = "Question: $userQuestion"
                )
                return@withContext Result.failure(Exception(errorMsg))
            }

            val json = JSONObject(responseBody)
            val candidates = json.optJSONArray("candidates")
            val firstCandidate = candidates?.optJSONObject(0)
            val content = firstCandidate?.optJSONObject("content")
            val parts = content?.optJSONArray("parts")

            val sb = StringBuilder()
            if (parts != null) {
                for (i in 0 until parts.length()) {
                    val p = parts.optJSONObject(i)
                    val t = p?.optString("text") ?: ""
                    if (t.isNotBlank()) {
                        sb.append(t)
                    }
                }
            }
            val answerText = sb.toString()

            if (answerText.isBlank()) {
                val errorMsg = "Gemini 未返回有效文本回复: $responseBody"
                AppLogger.logError(
                    category = "GEMINI_QA",
                    tag = "GeminiClient",
                    message = errorMsg
                )
                return@withContext Result.failure(Exception(errorMsg))
            }

            AppLogger.logInfo("GeminiClient", "Companion QA Answer received: $answerText")
            Result.success(cleanSpokenAnswer(answerText))
        } catch (e: Exception) {
            AppLogger.logError(
                category = "GEMINI_QA",
                tag = "GeminiClient",
                message = "Gemini QA 异常: ${e.message}",
                throwable = e,
                extraInfo = "Question: $userQuestion"
            )
            Result.failure(e)
        }
    }

    /**
     * Synthesizes audio using Gemini TTS.
     * Model: gemini-2.5-flash-preview-tts
     * For long text, processes in sentence segments to avoid server timeouts.
     */
    suspend fun synthesizeSpeech(
        apiKey: String,
        text: String,
        voiceName: String = "Kore",
        outputFile: File
    ): Result<File> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            val errorMsg = "Gemini API Key 未配置，无法调用云端 TTS"
            return@withContext Result.failure(IllegalStateException(errorMsg))
        }

        try {
            val segments = splitTextForTts(text, maxChars = 160)
            val audioSegments = mutableListOf<AudioChunkData>()

            for (seg in segments) {
                val segResult = synthesizeSingleSegment(apiKey, seg, voiceName)
                if (segResult.isFailure) {
                    val ex = segResult.exceptionOrNull() ?: Exception("TTS segment failed")
                    return@withContext Result.failure(ex)
                }
                audioSegments.add(segResult.getOrThrow())
            }

            outputFile.parentFile?.mkdirs()
            if (outputFile.exists()) {
                outputFile.delete()
            }

            val first = audioSegments.first()
            val isFirstWav = hasWavHeader(first.bytes)
            val isFirstMp3 = first.mimeType.contains("mp3") || first.mimeType.contains("mpeg") || isMp3Header(first.bytes)

            FileOutputStream(outputFile).use { fos ->
                if (isFirstMp3) {
                    // MP3 streams concatenate frames directly without a WAV header
                    for (seg in audioSegments) {
                        fos.write(seg.bytes)
                    }
                } else if (isFirstWav) {
                    // Extract original sample rate from the first segment's header
                    val sampleRate = extractSampleRateFromWav(first.bytes)
                    val pcmBytesList = audioSegments.map { seg ->
                        if (hasWavHeader(seg.bytes) && seg.bytes.size > 44) {
                            seg.bytes.copyOfRange(44, seg.bytes.size)
                        } else {
                            seg.bytes
                        }
                    }
                    val totalPcmSize = pcmBytesList.sumOf { it.size }
                    val wavHeader = createWavHeader(totalPcmSize, sampleRate, 1, 16)
                    fos.write(wavHeader)
                    for (pcm in pcmBytesList) {
                        fos.write(pcm)
                    }
                } else {
                    // Raw PCM (e.g. audio/l16; rate=24000; channels=1 or audio/pcm;rate=24000)
                    val sampleRate = parseSampleRateFromMime(first.mimeType)
                    val totalPcmSize = audioSegments.sumOf { it.bytes.size }
                    val wavHeader = createWavHeader(totalPcmSize, sampleRate, 1, 16)
                    fos.write(wavHeader)
                    for (seg in audioSegments) {
                        fos.write(seg.bytes)
                    }
                }
            }

            AppLogger.logInfo("GeminiClient", "Gemini TTS succeeded for ${outputFile.name} (${segments.size} segments, ${outputFile.length()} bytes)")
            Result.success(outputFile)
        } catch (e: Exception) {
            AppLogger.logError(
                category = "TTS",
                tag = "GeminiClient",
                message = "Gemini TTS 异常: ${e.message}",
                throwable = e,
                extraInfo = "Target file: ${outputFile.absolutePath}, text length: ${text.length}"
            )
            Result.failure(e)
        }
    }

    private fun parseSampleRateFromMime(mimeType: String): Int {
        val match = Regex("rate=(\\d+)").find(mimeType)
        if (match != null) {
            val rate = match.groupValues[1].toIntOrNull()
            if (rate != null && rate in 8000..96000) return rate
        }
        if (mimeType.contains("16000")) return 16000
        return 24000
    }

    private fun splitTextForTts(text: String, maxChars: Int = 260): List<String> {
        val trimmed = text.trim()
        if (trimmed.length <= maxChars) return listOf(trimmed)

        val list = mutableListOf<String>()
        val delimiters = charArrayOf('。', '！', '？', '；', '\n', '.', '!', '?', ';')
        var current = StringBuilder()

        val tokens = trimmed.split(Regex("(?<=[。！？；\n.!?;])"))
        for (token in tokens) {
            if (current.length + token.length <= maxChars) {
                current.append(token)
            } else {
                if (current.isNotEmpty()) {
                    list.add(current.toString().trim())
                    current = StringBuilder()
                }
                if (token.length > maxChars) {
                    var idx = 0
                    while (idx < token.length) {
                        val end = (idx + maxChars).coerceAtMost(token.length)
                        list.add(token.substring(idx, end).trim())
                        idx = end
                    }
                } else {
                    current.append(token)
                }
            }
        }
        if (current.isNotEmpty()) {
            list.add(current.toString().trim())
        }
        return list.filter { it.isNotBlank() }
    }

    data class AudioChunkData(
        val bytes: ByteArray,
        val mimeType: String
    )

    private fun synthesizeSingleSegment(
        apiKey: String,
        text: String,
        voiceName: String
    ): Result<AudioChunkData> {
        val effectiveVoice = if (voiceName.isNotBlank()) voiceName else "Aoede"
        val promptText = "Read the following text aloud: $text"

        val requestJson = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", promptText) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("responseModalities", JSONArray().apply {
                    put("AUDIO")
                })
                put("speechConfig", JSONObject().apply {
                    put("voiceConfig", JSONObject().apply {
                        put("prebuiltVoiceConfig", JSONObject().apply {
                            put("voiceName", effectiveVoice)
                        })
                    })
                })
            })
        }

        var lastError: Exception? = null

        // Try models with automatic failover (gemini-3.1-flash-tts-preview -> 2.5-flash -> 2.5-pro)
        for (modelName in TTS_MODELS) {
            try {
                val url = "$BASE_URL/v1beta/models/$modelName:generateContent?key=$apiKey"
                val request = Request.Builder()
                    .url(url)
                    .post(requestJson.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .build()

                val response = okHttpClient.newCall(request).execute()
                val responseBody = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    val errorMsg = "Gemini TTS [$modelName] HTTP ${response.code}: $responseBody"
                    AppLogger.logInfo("GeminiClient", errorMsg)
                    lastError = Exception(errorMsg)
                    continue
                }

                val json = JSONObject(responseBody)
                val candidates = json.optJSONArray("candidates")
                val firstCandidate = candidates?.optJSONObject(0)
                val parts = firstCandidate?.optJSONObject("content")?.optJSONArray("parts")

                var audioBase64: String? = null
                var mimeType = ""

                if (parts != null) {
                    for (i in 0 until parts.length()) {
                        val part = parts.optJSONObject(i)
                        val inlineData = part?.optJSONObject("inlineData")
                        if (inlineData != null) {
                            audioBase64 = inlineData.optString("data")
                            mimeType = inlineData.optString("mimeType", "")
                            break
                        }
                    }
                }

                if (audioBase64.isNullOrEmpty()) {
                    lastError = Exception("Gemini TTS [$modelName] 未返回音频数据")
                    continue
                }

                val rawBytes = Base64.decode(audioBase64, Base64.DEFAULT)
                if (rawBytes.isEmpty()) {
                    lastError = Exception("Gemini TTS [$modelName] 解码音频长度为0")
                    continue
                }

                return Result.success(AudioChunkData(rawBytes, mimeType))
            } catch (e: Exception) {
                lastError = e
                AppLogger.logInfo("GeminiClient", "Gemini TTS [$modelName] call failed: ${e.message}")
            }
        }

        return Result.failure(lastError ?: Exception("所有 Gemini TTS 模型调用均失败"))
    }

    private fun hasWavHeader(bytes: ByteArray): Boolean {
        if (bytes.size < 12) return false
        return bytes[0] == 'R'.code.toByte() &&
               bytes[1] == 'I'.code.toByte() &&
               bytes[2] == 'F'.code.toByte() &&
               bytes[3] == 'F'.code.toByte() &&
               bytes[8] == 'W'.code.toByte() &&
               bytes[9] == 'A'.code.toByte() &&
               bytes[10] == 'V'.code.toByte() &&
               bytes[11] == 'E'.code.toByte()
    }

    private fun isMp3Header(bytes: ByteArray): Boolean {
        if (bytes.size < 3) return false
        // ID3 tag
        if (bytes[0] == 'I'.code.toByte() && bytes[1] == 'D'.code.toByte() && bytes[2] == '3'.code.toByte()) return true
        // MPEG frame sync (0xFF 0xFB, 0xFF 0xF3, 0xFF 0xF2, 0xFF 0xFA)
        if ((bytes[0].toInt() and 0xFF) == 0xFF && ((bytes[1].toInt() and 0xE0) == 0xE0)) return true
        return false
    }

    private fun extractSampleRateFromWav(bytes: ByteArray): Int {
        if (bytes.size < 28) return 24000
        val b0 = bytes[24].toInt() and 0xFF
        val b1 = bytes[25].toInt() and 0xFF
        val b2 = bytes[26].toInt() and 0xFF
        val b3 = bytes[27].toInt() and 0xFF
        val rate = b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
        return if (rate in 8000..96000) rate else 24000
    }

    private fun createWavHeader(
        totalAudioLen: Int,
        longSampleRate: Int,
        channels: Int,
        byteRate: Int
    ): ByteArray {
        val totalDataLen = totalAudioLen + 36
        val bitsPerSample = 16
        val subChunk1Size = 16
        val audioFormat = 1 // PCM
        val byteRateCalc = longSampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8

        val header = ByteArray(44)
        // RIFF
        header[0] = 'R'.code.toByte()
        header[1] = 'I'.code.toByte()
        header[2] = 'F'.code.toByte()
        header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte()
        header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte()
        header[7] = (totalDataLen shr 24 and 0xff).toByte()
        // WAVE
        header[8] = 'W'.code.toByte()
        header[9] = 'A'.code.toByte()
        header[10] = 'V'.code.toByte()
        header[11] = 'E'.code.toByte()
        // fmt
        header[12] = 'f'.code.toByte()
        header[13] = 'm'.code.toByte()
        header[14] = 't'.code.toByte()
        header[15] = ' '.code.toByte()
        header[16] = (subChunk1Size and 0xff).toByte()
        header[17] = (subChunk1Size shr 8 and 0xff).toByte()
        header[18] = (subChunk1Size shr 16 and 0xff).toByte()
        header[19] = (subChunk1Size shr 24 and 0xff).toByte()
        header[20] = (audioFormat and 0xff).toByte()
        header[21] = (audioFormat shr 8 and 0xff).toByte()
        header[22] = (channels and 0xff).toByte()
        header[23] = (channels shr 8 and 0xff).toByte()
        header[24] = (longSampleRate and 0xff).toByte()
        header[25] = (longSampleRate shr 8 and 0xff).toByte()
        header[26] = (longSampleRate shr 16 and 0xff).toByte()
        header[27] = (longSampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRateCalc and 0xff).toByte()
        header[29] = (byteRateCalc shr 8 and 0xff).toByte()
        header[30] = (byteRateCalc shr 16 and 0xff).toByte()
        header[31] = (byteRateCalc shr 24 and 0xff).toByte()
        header[32] = (blockAlign and 0xff).toByte()
        header[33] = (blockAlign shr 8 and 0xff).toByte()
        header[34] = (bitsPerSample and 0xff).toByte()
        header[35] = (bitsPerSample shr 8 and 0xff).toByte()
        // data
        header[36] = 'd'.code.toByte()
        header[37] = 'a'.code.toByte()
        header[38] = 't'.code.toByte()
        header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte()
        header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte()
        header[43] = (totalAudioLen shr 24 and 0xff).toByte()
        return header
    }

    private fun cleanSpokenAnswer(rawAnswer: String): String {
        var text = rawAnswer
            .replace(Regex("<think>[\\s\\S]*?</think>"), "") // strip thinking tokens if present
            .replace(Regex("#+\\s*"), "") // Markdown headers
            .replace(Regex("\\*\\*(.*?)\\*\\*"), "$1") // Bold
            .replace(Regex("\\*(.*?)\\*"), "$1") // Italic
            .replace(Regex("`.*?`"), "") // Code
            .replace(Regex("[-*]\\s+"), "") // Bullet points
            .replace(Regex("[\\r\\n]+"), " ")
            .trim()

        text = text.trimStart(',', '，', '。', '.', '、', ' ')
        if (text.isNotBlank() && !text.endsWith("。") && !text.endsWith("！") && !text.endsWith("？")) {
            text += "。"
        }
        return text
    }
}
