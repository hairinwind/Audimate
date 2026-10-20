package com.example.parser

import com.example.data.models.ChunkEntity

object DocumentChunker {
    private const val MIN_CHUNK_SIZE = 450
    private const val TARGET_CHUNK_SIZE = 650
    private const val MAX_CHUNK_SIZE = 850

    fun chunkText(bookId: String, cleanedText: String): List<ChunkEntity> {
        val paragraphs = cleanedText.split("\n\n").map { it.trim() }.filter { it.isNotEmpty() }
        val chunks = mutableListOf<String>()

        val currentBuilder = StringBuilder()

        for (para in paragraphs) {
            if (para.length > MAX_CHUNK_SIZE) {
                // If this single paragraph is larger than MAX_CHUNK_SIZE, split it by sentence endings
                if (currentBuilder.isNotEmpty()) {
                    chunks.add(currentBuilder.toString().trim())
                    currentBuilder.clear()
                }

                val sentences = splitIntoSentences(para)
                for (sentence in sentences) {
                    if (currentBuilder.length + sentence.length > TARGET_CHUNK_SIZE && currentBuilder.length >= MIN_CHUNK_SIZE) {
                        chunks.add(currentBuilder.toString().trim())
                        currentBuilder.clear()
                    }
                    currentBuilder.append(sentence)
                }
            } else {
                // Normal paragraph
                if (currentBuilder.length + para.length > MAX_CHUNK_SIZE && currentBuilder.length >= MIN_CHUNK_SIZE) {
                    chunks.add(currentBuilder.toString().trim())
                    currentBuilder.clear()
                }
                if (currentBuilder.isNotEmpty()) {
                    currentBuilder.append("\n\n")
                }
                currentBuilder.append(para)
            }
        }

        if (currentBuilder.isNotEmpty()) {
            val remaining = currentBuilder.toString().trim()
            if (remaining.isNotEmpty()) {
                chunks.add(remaining)
            }
        }

        // Map to ChunkEntity with sequential chunkIndex starting from 0
        return chunks.mapIndexed { index, content ->
            ChunkEntity(
                bookId = bookId,
                chunkIndex = index,
                textContent = content,
                status = ChunkEntity.STATUS_PENDING,
                createdAt = System.currentTimeMillis()
            )
        }
    }

    private fun splitIntoSentences(text: String): List<String> {
        val result = mutableListOf<String>()
        val sentenceEndings = setOf('。', '！', '？', '…', '.', '!', '?')
        val sb = StringBuilder()

        for (ch in text) {
            sb.append(ch)
            if (ch in sentenceEndings) {
                result.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) {
            result.add(sb.toString())
        }
        return result
    }
}
