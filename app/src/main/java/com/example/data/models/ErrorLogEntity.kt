package com.example.data.models

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "error_logs",
    indices = [
        Index(value = ["timestamp"])
    ]
)
data class ErrorLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "timestamp")
    val timestamp: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "category")
    val category: String, // "TTS", "GEMINI_QA", "PLAYER", "STT", "PIPELINE", "DATABASE", "SYSTEM"

    @ColumnInfo(name = "tag")
    val tag: String,

    @ColumnInfo(name = "message")
    val message: String,

    @ColumnInfo(name = "stack_trace")
    val stackTrace: String,

    @ColumnInfo(name = "extra_info")
    val extraInfo: String? = null
)
