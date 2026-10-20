package com.example.data.repository

import android.content.Context
import com.example.data.dao.BookDao
import com.example.data.dao.ChunkDao
import com.example.data.models.BookEntity
import com.example.data.models.ChunkEntity
import kotlinx.coroutines.flow.Flow
import java.io.File

class BookRepository(
    private val context: Context,
    private val bookDao: BookDao,
    private val chunkDao: ChunkDao
) {
    val allBooks: Flow<List<BookEntity>> = bookDao.getAllBooksFlow()

    fun getBookFlow(bookId: String): Flow<BookEntity?> = bookDao.getBookByIdFlow(bookId)

    fun getChunksFlow(bookId: String): Flow<List<ChunkEntity>> = chunkDao.getChunksByBookIdFlow(bookId)

    suspend fun getBook(bookId: String): BookEntity? = bookDao.getBookById(bookId)

    suspend fun getChunks(bookId: String): List<ChunkEntity> = chunkDao.getChunksByBookId(bookId)

    suspend fun getChunk(bookId: String, chunkIndex: Int): ChunkEntity? = chunkDao.getChunkByIndex(bookId, chunkIndex)

    suspend fun updatePlaybackProgress(bookId: String, chunkIndex: Int, positionMs: Long) {
        bookDao.updatePlaybackProgress(bookId, chunkIndex, positionMs)
    }

    suspend fun saveNewBookWithChunks(book: BookEntity, chunks: List<ChunkEntity>) {
        val bookAudioDir = getAudioDir(book.id)
        if (!bookAudioDir.exists()) {
            bookAudioDir.mkdirs()
        }
        bookDao.insertBook(book)
        chunkDao.insertAll(chunks)
    }

    suspend fun markChunkCompleted(chunkId: Long, bookId: String, audioPath: String, durationMs: Long) {
        chunkDao.markCompleted(chunkId, audioPath, durationMs)
        val completed = chunkDao.countCompletedChunks(bookId)
        bookDao.updateCompletedCount(bookId, completed)
    }

    suspend fun markChunkProcessing(chunkId: Long) {
        chunkDao.markProcessing(chunkId)
    }

    suspend fun markChunkFailed(chunkId: Long, errorMessage: String) {
        chunkDao.markFailed(chunkId, errorMessage)
    }

    suspend fun deleteBook(bookId: String) {
        chunkDao.deleteChunksByBookId(bookId)
        bookDao.deleteBookById(bookId)
        val dir = getBookDir(bookId)
        dir.deleteRecursively()
    }

    suspend fun clearAudioCacheForBook(bookId: String) {
        val audioDir = getAudioDir(bookId)
        audioDir.listFiles()?.forEach { if (it.isFile) it.delete() }
        val chunks = chunkDao.getChunksByBookId(bookId)
        chunks.forEach { chunk ->
            chunkDao.updateStatus(chunk.id, ChunkEntity.STATUS_PENDING, null)
        }
        bookDao.updateCompletedCount(bookId, 0)
    }

    fun getBookDir(bookId: String): File {
        return File(context.filesDir, "books/$bookId")
    }

    fun getAudioDir(bookId: String): File {
        val audioDir = File(getBookDir(bookId), "audios")
        if (!audioDir.exists()) {
            audioDir.mkdirs()
        }
        return audioDir
    }

    fun getChunkAudioFile(bookId: String, chunkIndex: Int): File {
        val fileName = String.format("chunk_%04d.wav", chunkIndex)
        return File(getAudioDir(bookId), fileName)
    }
}
