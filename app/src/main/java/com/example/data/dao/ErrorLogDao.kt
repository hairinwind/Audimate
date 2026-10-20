package com.example.data.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.example.data.models.ErrorLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ErrorLogDao {
    @Query("SELECT * FROM error_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getAllLogsFlow(limit: Int = 100): Flow<List<ErrorLogEntity>>

    @Query("SELECT * FROM error_logs WHERE timestamp >= :sinceTimestamp ORDER BY timestamp DESC")
    fun getLogsSinceFlow(sinceTimestamp: Long): Flow<List<ErrorLogEntity>>

    @Query("SELECT * FROM error_logs WHERE category = :category ORDER BY timestamp DESC LIMIT :limit")
    fun getLogsByCategoryFlow(category: String, limit: Int = 100): Flow<List<ErrorLogEntity>>

    @Insert
    suspend fun insertLog(log: ErrorLogEntity): Long

    @Query("DELETE FROM error_logs WHERE timestamp < :cutoffTimestamp")
    suspend fun deleteLogsOlderThan(cutoffTimestamp: Long): Int

    @Query("DELETE FROM error_logs")
    suspend fun clearAllLogs()

    @Query("SELECT COUNT(*) FROM error_logs")
    fun getErrorCountFlow(): Flow<Int>
}
