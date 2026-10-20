package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.models.ChunkEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChunkDao {
    @Query("SELECT * FROM chunks WHERE book_id = :bookId ORDER BY chunk_index ASC")
    fun getChunksByBookIdFlow(bookId: String): Flow<List<ChunkEntity>>

    @Query("SELECT * FROM chunks WHERE book_id = :bookId ORDER BY chunk_index ASC")
    suspend fun getChunksByBookId(bookId: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE book_id = :bookId AND chunk_index = :chunkIndex LIMIT 1")
    suspend fun getChunkByIndex(bookId: String, chunkIndex: Int): ChunkEntity?

    @Query("SELECT * FROM chunks WHERE book_id = :bookId AND status = :status ORDER BY chunk_index ASC")
    suspend fun getChunksByStatus(bookId: String, status: String): List<ChunkEntity>

    @Query("SELECT * FROM chunks WHERE book_id = :bookId AND (status = 'PENDING' OR status = 'FAILED') ORDER BY chunk_index ASC LIMIT :limit")
    suspend fun getNextPendingChunks(bookId: String, limit: Int): List<ChunkEntity>

    @Query("SELECT COUNT(*) FROM chunks WHERE book_id = :bookId AND status = 'COMPLETED'")
    suspend fun countCompletedChunks(bookId: String): Int

    @Query("SELECT COUNT(*) FROM chunks WHERE book_id = :bookId")
    suspend fun countTotalChunks(bookId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(chunks: List<ChunkEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: ChunkEntity): Long

    @Update
    suspend fun update(chunk: ChunkEntity)

    @Query("UPDATE chunks SET status = :status, error_message = :errorMessage WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, errorMessage: String? = null)

    @Query("UPDATE chunks SET status = 'COMPLETED', audio_path = :audioPath, duration_ms = :durationMs, error_message = null WHERE id = :id")
    suspend fun markCompleted(id: Long, audioPath: String, durationMs: Long)

    @Query("UPDATE chunks SET status = 'PROCESSING', retry_count = retry_count + 1 WHERE id = :id")
    suspend fun markProcessing(id: Long)

    @Query("UPDATE chunks SET status = 'FAILED', error_message = :errorMessage WHERE id = :id")
    suspend fun markFailed(id: Long, errorMessage: String)

    @Query("DELETE FROM chunks WHERE book_id = :bookId")
    suspend fun deleteChunksByBookId(bookId: String)
}
