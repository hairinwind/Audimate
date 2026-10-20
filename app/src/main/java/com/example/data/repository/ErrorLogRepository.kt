package com.example.data.repository

import com.example.data.dao.ErrorLogDao
import com.example.data.models.ErrorLogEntity
import kotlinx.coroutines.flow.Flow

class ErrorLogRepository(private val errorLogDao: ErrorLogDao) {
    val allLogs: Flow<List<ErrorLogEntity>> = errorLogDao.getAllLogsFlow()

    fun getLogsByCategory(category: String): Flow<List<ErrorLogEntity>> =
        errorLogDao.getLogsByCategoryFlow(category)

    fun getLogsSince(sinceTimestamp: Long): Flow<List<ErrorLogEntity>> =
        errorLogDao.getLogsSinceFlow(sinceTimestamp)

    suspend fun insert(log: ErrorLogEntity): Long = errorLogDao.insertLog(log)

    suspend fun clearAll() = errorLogDao.clearAllLogs()
}
