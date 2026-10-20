package com.example.pipeline

import com.example.data.models.ChunkEntity
import com.example.data.repository.BookRepository
import com.example.data.repository.SettingsRepository
import com.example.logging.AppLogger
import com.example.tts.AudioSynthesisManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

class TtsPrefetchPipeline(
    private val bookRepository: BookRepository,
    private val audioSynthesisManager: AudioSynthesisManager,
    private val settingsRepository: SettingsRepository
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pipelineJob: Job? = null

    // Circuit breaker state
    private val consecutiveFailures = AtomicInteger(0)
    private val isCircuitBreakerOpen = AtomicBoolean(false)
    private val MAX_CONSECUTIVE_FAILURES = 5
    private val CIRCUIT_BREAKER_COOLDOWN_MS = 30000L

    // Active in-flight chunks to prevent duplicate conversion
    private val processingChunks = ConcurrentHashMap.newKeySet<Long>()

    private val _isPipelineActive = MutableStateFlow(false)
    val isPipelineActive: StateFlow<Boolean> = _isPipelineActive.asStateFlow()

    private val _pipelineStatus = MutableStateFlow("待命")
    val pipelineStatus: StateFlow<String> = _pipelineStatus.asStateFlow()

    private val _isReadyToPlay = MutableStateFlow(false)
    val isReadyToPlay: StateFlow<Boolean> = _isReadyToPlay.asStateFlow()

    var onFirstChunkReady: ((String, Int) -> Unit)? = null

    /**
     * Starts the prefetch pipeline:
     * 1. 秒开策略: 优先并发转换第 0～2 个分块；第 0 块转换完成并落盘后，立即激活播放就绪状态。
     * 2. 边播边预取: 后台工作队列维持 3～5 个并发 Worker，依次向后批量请求未转换的 Chunk。
     */
    fun startPipeline(bookId: String, currentChunkIndex: Int = 0) {
        pipelineJob?.cancel()
        _isReadyToPlay.value = false

        pipelineJob = scope.launch {
            _isPipelineActive.value = true
            _pipelineStatus.value = "秒开流水线启动中..."
            consecutiveFailures.set(0)
            isCircuitBreakerOpen.set(false)

            AppLogger.logInfo("TtsPrefetchPipeline", "Starting quick-start prefetch pipeline for book $bookId at index $currentChunkIndex")

            // Step 1: 秒开策略 - 检查当前块是否已就绪
            val currentChunk = bookRepository.getChunk(bookId, currentChunkIndex)
            val currentAudio = bookRepository.getChunkAudioFile(bookId, currentChunkIndex)
            if (currentChunk?.status == ChunkEntity.STATUS_COMPLETED && currentAudio.exists() && currentAudio.length() > 0) {
                _isReadyToPlay.value = true
                _pipelineStatus.value = "第 ${currentChunkIndex + 1} 块已就绪，可立即收听"
                onFirstChunkReady?.invoke(bookId, currentChunkIndex)
            }

            // Step 2: 优先并发转换第 0～2 个分块 (Chunks 0, 1, 2)
            prioritizeImmediateChunksConcurrently(bookId, currentChunkIndex)

            // Step 3: 后台边播边预取 (维持 3～5 个并发 Worker 向后请求)
            runPrefetchLoop(bookId, currentChunkIndex)
        }
    }

    /**
     * 秒开策略：优先并发转换第 0～2 个分块；第 0 块转换完成并落盘后，立即激活播放就绪状态。
     */
    private suspend fun prioritizeImmediateChunksConcurrently(bookId: String, startIndex: Int) {
        val priorityIndices = listOf(startIndex, startIndex + 1, startIndex + 2)
        val chunksToConvert = priorityIndices.mapNotNull { idx ->
            val chunk = bookRepository.getChunk(bookId, idx)
            val file = bookRepository.getChunkAudioFile(bookId, idx)
            if (chunk != null && (chunk.status != ChunkEntity.STATUS_COMPLETED || !file.exists() || file.length() == 0L)) {
                chunk
            } else null
        }

        if (chunksToConvert.isEmpty()) {
            _isReadyToPlay.value = true
            return
        }

        _pipelineStatus.value = "秒开预热：并发转换前 ${chunksToConvert.size} 块..."

        // Launch concurrent conversion jobs
        val priorityJobs = chunksToConvert.map { chunk ->
            scope.async {
                val success = convertSingleChunk(chunk)
                if (success && chunk.chunkIndex == startIndex) {
                    // 第 0 块转换完成并落盘后，立即激活播放就绪状态！
                    _isReadyToPlay.value = true
                    _pipelineStatus.value = "第 ${startIndex + 1} 块已落盘就绪，立即激活播放"
                    AppLogger.logInfo("TtsPrefetchPipeline", "Chunk $startIndex ready! Activating playback ready state.")
                    onFirstChunkReady?.invoke(bookId, startIndex)
                }
                success
            }
        }

        priorityJobs.awaitAll()

        // Ensure ready state is signaled
        val firstAudio = bookRepository.getChunkAudioFile(bookId, startIndex)
        if (firstAudio.exists() && firstAudio.length() > 0) {
            _isReadyToPlay.value = true
        }
    }

    /**
     * 边播边预取（伴随下载）：维持 3～5 个并发 Worker，依次向后批量请求未转换的 Chunk。
     * 预处理的 chunk 数值根据用户设置 prefetchAheadCount 进行上限控制。
     */
    private suspend fun runPrefetchLoop(bookId: String, currentChunkIndex: Int) {
        while (_isPipelineActive.value) {
            // Check circuit breaker
            if (isCircuitBreakerOpen.get()) {
                _pipelineStatus.value = "遇到多次错误，熔断休眠 30 秒..."
                AppLogger.logInfo("TtsPrefetchPipeline", "Circuit breaker active. Sleeping 30s.")
                delay(CIRCUIT_BREAKER_COOLDOWN_MS)
                isCircuitBreakerOpen.set(false)
                consecutiveFailures.set(0)
                _pipelineStatus.value = "熔断恢复，继续向后预取..."
            }

            val concurrency = settingsRepository.concurrency.value.coerceIn(3, 5)
            val maxAhead = settingsRepository.prefetchAheadCount.value.coerceIn(2, 50)
            val semaphore = Semaphore(concurrency)

            val allChunks = bookRepository.getChunks(bookId)
            if (allChunks.isEmpty()) {
                delay(1000)
                continue
            }

            // Filter chunks within the user-configured prefetch ahead window
            val pendingChunks = allChunks.filter { chunk ->
                val audioFile = bookRepository.getChunkAudioFile(bookId, chunk.chunkIndex)
                val needsConversion = (chunk.status != ChunkEntity.STATUS_COMPLETED || !audioFile.exists() || audioFile.length() == 0L)
                val withinWindow = chunk.chunkIndex >= currentChunkIndex && chunk.chunkIndex <= (currentChunkIndex + maxAhead)
                needsConversion && withinWindow && !processingChunks.contains(chunk.id)
            }.sortedBy { it.chunkIndex }

            if (pendingChunks.isEmpty()) {
                _pipelineStatus.value = "当前已预处理至设定上限（领先 $maxAhead 块）"
                delay(2000)
                continue
            }

            _pipelineStatus.value = "边播边预取中 (维持 $concurrency 个 Worker 并发)..."
            val batch = pendingChunks.take(concurrency)
            val workerJobs = batch.map { chunk ->
                scope.launch {
                    semaphore.withPermit {
                        convertSingleChunk(chunk)
                    }
                }
            }

            workerJobs.forEach { it.join() }
            delay(300)
        }
    }

    /**
     * Synchronously prioritizes and immediately synthesizes a specific chunk if requested by player.
     */
    suspend fun prioritizeChunk(bookId: String, chunkIndex: Int): Boolean {
        val chunk = bookRepository.getChunk(bookId, chunkIndex) ?: return false
        val audioFile = bookRepository.getChunkAudioFile(bookId, chunkIndex)
        if (chunk.status == ChunkEntity.STATUS_COMPLETED && audioFile.exists() && audioFile.length() > 0) {
            return true
        }
        return convertSingleChunk(chunk)
    }

    private suspend fun convertSingleChunk(chunk: ChunkEntity): Boolean {
        if (!processingChunks.add(chunk.id)) {
            return false // Already in flight
        }

        try {
            bookRepository.markChunkProcessing(chunk.id)
            _pipelineStatus.value = "正在合成第 ${chunk.chunkIndex + 1} 块..."

            val destinationFile = bookRepository.getChunkAudioFile(chunk.bookId, chunk.chunkIndex)

            // Retry logic (up to 2 retries)
            var attempt = 0
            var success = false
            var lastError = ""

            while (attempt < 2 && !success) {
                attempt++
                val result = audioSynthesisManager.synthesizeChunk(chunk.textContent, destinationFile)
                if (result.isSuccess) {
                    val synthesisResult = result.getOrThrow()
                    bookRepository.markChunkCompleted(
                        chunkId = chunk.id,
                        bookId = chunk.bookId,
                        audioPath = synthesisResult.file.absolutePath,
                        durationMs = synthesisResult.durationMs
                    )
                    consecutiveFailures.set(0)
                    success = true
                    AppLogger.logInfo("TtsPrefetchPipeline", "Chunk ${chunk.chunkIndex} completed via ${synthesisResult.engineUsed} (${destinationFile.length()} bytes)")
                } else {
                    lastError = result.exceptionOrNull()?.message ?: "Unknown error"
                    val backoffMs = 1000L * attempt
                    AppLogger.logInfo(
                        "TtsPrefetchPipeline",
                        "Chunk ${chunk.chunkIndex} attempt $attempt error: $lastError. Retrying..."
                    )
                    delay(backoffMs)
                }
            }

            if (success) {
                return true
            } else {
                bookRepository.markChunkFailed(chunk.id, lastError)
                val failures = consecutiveFailures.incrementAndGet()
                if (failures >= MAX_CONSECUTIVE_FAILURES) {
                    isCircuitBreakerOpen.set(true)
                }
                return false
            }
        } catch (e: Exception) {
            AppLogger.logError(
                category = "PIPELINE",
                tag = "TtsPrefetchPipeline",
                message = "Exception processing chunk ${chunk.chunkIndex}: ${e.message}",
                throwable = e
            )
            bookRepository.markChunkFailed(chunk.id, e.message ?: "Unknown exception")
            return false
        } finally {
            processingChunks.remove(chunk.id)
        }
    }

    fun stopPipeline() {
        _isPipelineActive.value = false
        _pipelineStatus.value = "已暂停预取"
        pipelineJob?.cancel()
        pipelineJob = null
    }
}
