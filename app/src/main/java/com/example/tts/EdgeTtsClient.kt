package com.example.tts

import com.example.logging.AppLogger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import java.util.concurrent.TimeUnit

object EdgeTtsConstants {
    const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
    const val CHROMIUM_MAJOR_VERSION = "143"
    const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
    const val WIN_EPOCH = 11644473600L

    // Popular Edge Chinese & English neural voices
    val AVAILABLE_VOICES = listOf(
        EdgeVoice("zh-CN-YunxiNeural", "云希 (阳光生动·有声小说首选)", "男声", "生动热烈，表现力极强"),
        EdgeVoice("zh-CN-XiaoxiaoNeural", "晓晓 (温和亲切·自然逼真)", "女声", "富有感染力，咬字清晰"),
        EdgeVoice("zh-CN-YunjianNeural", "云健 (沉稳磁性·评书解说)", "男声", "磁性男声，适合严肃与悬疑"),
        EdgeVoice("zh-CN-XiaoyiNeural", "晓伊 (轻柔舒缓·故事伴读)", "女声", "温柔细腻，适合夜间长途"),
        EdgeVoice("zh-CN-YunyangNeural", "云扬 (专业稳健·播音级)", "男声", "广播播音腔，发音标准"),
        EdgeVoice("zh-CN-liaoning-XiaobeiNeural", "晓北 (东北特色·生动幽默)", "女声", "特色方言幽默风趣")
    )
}

data class EdgeVoice(
    val id: String,
    val name: String,
    val gender: String,
    val description: String
)

object EdgeTtsHelper {

    fun getSecMsGec(): String {
        var ticks = (System.currentTimeMillis() / 1000.0) + EdgeTtsConstants.WIN_EPOCH
        ticks -= (ticks % 300)
        ticks *= 10_000_000.0
        val strToHash = String.format(Locale.US, "%.0f%s", ticks, EdgeTtsConstants.TRUSTED_CLIENT_TOKEN)
        val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    fun generateMuid(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    fun dateToString(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    fun buildWssUrl(connectionId: String = UUID.randomUUID().toString().replace("-", "")): String {
        val secMsGec = getSecMsGec()
        return "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
                "?TrustedClientToken=${EdgeTtsConstants.TRUSTED_CLIENT_TOKEN}" +
                "&ConnectionId=$connectionId" +
                "&Sec-MS-GEC=$secMsGec" +
                "&Sec-MS-GEC-Version=${EdgeTtsConstants.SEC_MS_GEC_VERSION}"
    }

    fun formatRate(rateMultiplier: Float): String {
        val percent = ((rateMultiplier - 1.0f) * 100).toInt()
        return if (percent >= 0) "+$percent%" else "$percent%"
    }

    fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
            .filter { c ->
                val code = c.code
                !((code in 0..8) || (code in 11..12) || (code in 14..31))
            }
    }

    fun buildSsml(voice: String, text: String, rate: String = "+0%", pitch: String = "+0Hz", volume: String = "+0%"): String {
        val escaped = escapeXml(text)
        return "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                "<voice name='$voice'>" +
                "<prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
                escaped +
                "</prosody></voice></speak>"
    }
}

class EdgeTtsClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {

    suspend fun synthesizeToFile(
        text: String,
        outputFile: File,
        voice: String = "zh-CN-YunxiNeural",
        speed: Float = 1.0f
    ): Result<File> = withContext(Dispatchers.IO) {
        if (text.isBlank()) {
            return@withContext Result.failure(IllegalArgumentException("Text cannot be empty"))
        }

        outputFile.parentFile?.mkdirs()
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val deferred = CompletableDeferred<Result<Long>>()
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val wssUrl = EdgeTtsHelper.buildWssUrl(connectionId)

        val request = Request.Builder()
            .url(wssUrl)
            .addHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/${EdgeTtsConstants.CHROMIUM_MAJOR_VERSION}.0.0.0 Safari/537.36 Edg/${EdgeTtsConstants.CHROMIUM_MAJOR_VERSION}.0.0.0")
            .addHeader("Accept-Encoding", "gzip, deflate, br, zstd")
            .addHeader("Accept-Language", "en-US,en;q=0.9")
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("Sec-WebSocket-Version", "13")
            .addHeader("Cookie", "muid=${EdgeTtsHelper.generateMuid()};")
            .build()

        var totalAudioBytes = 0L
        val fos = FileOutputStream(outputFile)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                try {
                    // 1. Send speech.config
                    val configMsg = "X-Timestamp:${EdgeTtsHelper.dateToString()}\r\n" +
                            "Content-Type:application/json; charset=utf-8\r\n" +
                            "Path:speech.config\r\n\r\n" +
                            "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{\"sentenceBoundaryEnabled\":\"false\",\"wordBoundaryEnabled\":\"false\"},\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}\r\n"
                    webSocket.send(configMsg)

                    // 2. Send SSML
                    val requestId = UUID.randomUUID().toString().replace("-", "")
                    val timestamp = EdgeTtsHelper.dateToString()
                    val rateStr = EdgeTtsHelper.formatRate(speed)
                    val ssml = EdgeTtsHelper.buildSsml(voice, text, rate = rateStr)
                    val ssmlMsg = "X-RequestId:$requestId\r\n" +
                            "Content-Type:application/ssml+xml\r\n" +
                            "X-Timestamp:${timestamp}Z\r\n" +
                            "Path:ssml\r\n\r\n" +
                            ssml
                    webSocket.send(ssmlMsg)
                } catch (e: Exception) {
                    AppLogger.logError("TTS", "EdgeTtsClient", "Error in onOpen: ${e.message}", e)
                    deferred.complete(Result.failure(e))
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                try {
                    if (bytes.size < 2) return
                    val headerLength = ((bytes[0].toInt() and 0xFF) shl 8) or (bytes[1].toInt() and 0xFF)
                    if (bytes.size >= 2 + headerLength) {
                        val headerBytes = bytes.substring(2, 2 + headerLength).toByteArray()
                        val headerStr = String(headerBytes, Charsets.UTF_8)
                        if (headerStr.contains("Path:audio")) {
                            val audioData = bytes.substring(2 + headerLength).toByteArray()
                            if (audioData.isNotEmpty()) {
                                fos.write(audioData)
                                totalAudioBytes += audioData.size
                            }
                        }
                    }
                } catch (e: Exception) {
                    AppLogger.logError("TTS", "EdgeTtsClient", "Error processing audio chunk: ${e.message}", e)
                }
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    try {
                        fos.flush()
                        fos.close()
                    } catch (e: Exception) {
                        // ignore
                    }
                    webSocket.close(1000, "Finished")
                    deferred.complete(Result.success(totalAudioBytes))
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                try {
                    fos.flush()
                    fos.close()
                } catch (e: Exception) {
                    // ignore
                }
                val msg = "Edge TTS WebSocket error: ${t.message} (code=${response?.code})"
                AppLogger.logError("TTS", "EdgeTtsClient", msg, t)
                deferred.complete(Result.failure(Exception(msg, t)))
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                try {
                    fos.flush()
                    fos.close()
                } catch (e: Exception) {
                    // ignore
                }
                if (!deferred.isCompleted) {
                    deferred.complete(Result.success(totalAudioBytes))
                }
            }
        }

        val webSocket = client.newWebSocket(request, listener)

        try {
            val synthesisResult = withTimeoutOrNull(30000L) {
                deferred.await()
            } ?: Result.failure(Exception("Edge TTS synthesis timed out (30s)"))

            if (synthesisResult.isSuccess && outputFile.exists() && outputFile.length() > 0) {
                AppLogger.logInfo(
                    "EdgeTtsClient",
                    "Edge TTS synthesized successfully: ${outputFile.name} (${outputFile.length()} bytes, voice: $voice)"
                )
                Result.success(outputFile)
            } else {
                val ex = synthesisResult.exceptionOrNull() ?: Exception("Edge TTS generated 0 bytes")
                Result.failure(ex)
            }
        } catch (e: Exception) {
            webSocket.cancel()
            Result.failure(e)
        } finally {
            try {
                fos.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }
}
