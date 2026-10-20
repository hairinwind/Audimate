package com.example.data.models

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String, // book_uuid
    val title: String,
    val author: String = "未知作者",
    val sourceFileName: String? = null,
    val currentChunkIndex: Int = 0,
    val currentPositionMs: Long = 0L,
    val totalChunks: Int = 0,
    val completedChunks: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val lastPlayedAt: Long = System.currentTimeMillis()
)
