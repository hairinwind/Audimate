package com.example.logging

import android.content.Context
import android.util.Log
import com.example.data.database.AppDatabase
import com.example.data.models.ErrorLogEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.PrintWriter
import java.io.StringWriter

object AppLogger {
    private const val DEFAULT_TAG = "DriveReader"
    private const val SEVEN_DAYS_MS = 7L * 24L * 60L * 60L * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var database: AppDatabase? = null

    // Latest error for immediate onscreen banner display
    private val _latestError = MutableStateFlow<ErrorLogEntity?>(null)
    val latestError: StateFlow<ErrorLogEntity?> = _latestError.asStateFlow()

    fun init(context: Context) {
        if (database == null) {
            database = AppDatabase.getInstance(context)
            // Clean up logs older than 7 days upon initialization
            scope.launch {
                try {
                    val cutoff = System.currentTimeMillis() - SEVEN_DAYS_MS
                    val deleted = database?.errorLogDao()?.deleteLogsOlderThan(cutoff) ?: 0
                    Log.d(DEFAULT_TAG, "Pruned $deleted error logs older than 7 days.")
                } catch (e: Exception) {
                    Log.e(DEFAULT_TAG, "Failed to prune old logs: ${e.message}", e)
                }
            }

            // Install uncaught exception handler to capture crashes
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                logError(
                    category = "SYSTEM",
                    tag = "UncaughtException",
                    message = "Uncaught crash on thread ${thread.name}: ${throwable.message}",
                    throwable = throwable
                )
                defaultHandler?.uncaughtException(thread, throwable)
            }
        }
    }

    fun dismissLatestError() {
        _latestError.value = null
    }

    fun logError(
        category: String,
        tag: String = DEFAULT_TAG,
        message: String,
        throwable: Throwable? = null,
        extraInfo: String? = null
    ) {
        val stackTraceStr = if (throwable != null) {
            val sw = StringWriter()
            val pw = PrintWriter(sw)
            throwable.printStackTrace(pw)
            sw.toString()
        } else {
            val stackElements = Thread.currentThread().stackTrace
            stackElements.drop(3).take(15).joinToString("\n") { "  at $it" }
        }

        Log.e(tag, "[$category] $message\n$stackTraceStr")

        val errorEntity = ErrorLogEntity(
            timestamp = System.currentTimeMillis(),
            category = category,
            tag = tag,
            message = message,
            stackTrace = stackTraceStr,
            extraInfo = extraInfo
        )

        // Update active error banner for instant driver/tester visibility
        _latestError.value = errorEntity

        // Persist to Room for 7-day retrospective debugging
        scope.launch {
            try {
                database?.errorLogDao()?.insertLog(errorEntity)
            } catch (e: Exception) {
                Log.e(DEFAULT_TAG, "Failed to persist error log: ${e.message}", e)
            }
        }
    }

    fun logInfo(tag: String = DEFAULT_TAG, message: String) {
        Log.i(tag, message)
    }

    fun logDebug(tag: String = DEFAULT_TAG, message: String) {
        Log.d(tag, message)
    }
}
