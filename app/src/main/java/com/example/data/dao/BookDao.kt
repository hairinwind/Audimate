package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.data.models.BookEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastPlayedAt DESC")
    fun getAllBooksFlow(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    fun getBookByIdFlow(id: String): Flow<BookEntity?>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: BookEntity)

    @Update
    suspend fun updateBook(book: BookEntity)

    @Query("UPDATE books SET currentChunkIndex = :chunkIndex, currentPositionMs = :positionMs, lastPlayedAt = :timestamp WHERE id = :bookId")
    suspend fun updatePlaybackProgress(bookId: String, chunkIndex: Int, positionMs: Long, timestamp: Long = System.currentTimeMillis())

    @Query("UPDATE books SET completedChunks = :completedCount WHERE id = :bookId")
    suspend fun updateCompletedCount(bookId: String, completedCount: Int)

    @Query("DELETE FROM books WHERE id = :id")
    suspend fun deleteBookById(id: String)
}
