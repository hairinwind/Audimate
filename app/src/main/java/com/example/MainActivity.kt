package com.example

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.example.data.database.AppDatabase
import com.example.data.repository.BookRepository
import com.example.data.repository.ErrorLogRepository
import com.example.data.repository.SettingsRepository
import com.example.logging.AppLogger
import com.example.pipeline.TtsPrefetchPipeline
import com.example.service.AudiobookMediaService
import com.example.statemachine.InteractionStateMachine
import com.example.tts.AudioSynthesisManager
import com.example.ui.books.BookLibraryScreen
import com.example.ui.errors.ErrorHistoryScreen
import com.example.ui.errors.FloatingErrorBanner
import com.example.ui.player.DrivingPlayerScreen
import com.example.ui.settings.SettingsScreen
import com.example.ui.theme.MyApplicationTheme

enum class Screen {
    LIBRARY,
    PLAYER,
    SETTINGS,
    ERROR_HISTORY
}

class MainActivity : ComponentActivity() {

    private lateinit var database: AppDatabase
    private lateinit var bookRepository: BookRepository
    private lateinit var errorLogRepository: ErrorLogRepository
    private lateinit var settingsRepository: SettingsRepository
    private lateinit var audioSynthesisManager: AudioSynthesisManager
    private lateinit var stateMachine: InteractionStateMachine
    private lateinit var prefetchPipeline: TtsPrefetchPipeline

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // 1. Initialize Logging & Exception Handlers
        AppLogger.init(applicationContext)

        // 2. Initialize Repositories & Managers
        database = AppDatabase.getInstance(this)
        bookRepository = BookRepository(this, database.bookDao(), database.chunkDao())
        errorLogRepository = ErrorLogRepository(database.errorLogDao())
        settingsRepository = SettingsRepository(this)
        audioSynthesisManager = AudioSynthesisManager(this, settingsRepository)

        // Pre-initialize ExoPlayer and ensure audio output is audible
        com.example.service.AudioPlayerManager.init(this)

        stateMachine = InteractionStateMachine(this, bookRepository, settingsRepository, audioSynthesisManager)
        prefetchPipeline = TtsPrefetchPipeline(bookRepository, audioSynthesisManager, settingsRepository)

        // 3. Start Foreground Media Service
        try {
            val serviceIntent = Intent(this, AudiobookMediaService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            AppLogger.logError(
                category = "SYSTEM",
                tag = "MainActivity",
                message = "Failed to start AudiobookMediaService: ${e.message}",
                throwable = e
            )
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    AudiobookApp(
                        bookRepository = bookRepository,
                        errorLogRepository = errorLogRepository,
                        settingsRepository = settingsRepository,
                        stateMachine = stateMachine,
                        prefetchPipeline = prefetchPipeline,
                        audioSynthesisManager = audioSynthesisManager
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        stateMachine.release()
        prefetchPipeline.stopPipeline()
        audioSynthesisManager.release()
        super.onDestroy()
    }
}

@Composable
fun AudiobookApp(
    bookRepository: BookRepository,
    errorLogRepository: ErrorLogRepository,
    settingsRepository: SettingsRepository,
    stateMachine: InteractionStateMachine,
    prefetchPipeline: TtsPrefetchPipeline,
    audioSynthesisManager: AudioSynthesisManager
) {
    var currentScreen by remember { mutableStateOf(Screen.LIBRARY) }
    var selectedBookId by remember { mutableStateOf<String?>(null) }

    // Request necessary runtime permissions (Audio recording for hands-free voice interrupt & Notifications)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val recordGranted = results[Manifest.permission.RECORD_AUDIO] == true
        if (recordGranted) {
            AppLogger.logInfo("MainActivity", "Audio recording permission granted for voice interaction")
            stateMachine.voiceManager.onPermissionGranted()
        } else {
            AppLogger.logInfo("MainActivity", "Audio recording permission denied; UI simulation controls available")
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    Box(modifier = Modifier.fillMaxSize()) {
        when (currentScreen) {
            Screen.LIBRARY -> {
                BookLibraryScreen(
                    bookRepository = bookRepository,
                    onSelectBook = { id ->
                        selectedBookId = id
                        currentScreen = Screen.PLAYER
                    },
                    onNavigateToSettings = { currentScreen = Screen.SETTINGS },
                    onNavigateToErrors = { currentScreen = Screen.ERROR_HISTORY }
                )
            }

            Screen.PLAYER -> {
                val bookId = selectedBookId
                if (bookId != null) {
                    DrivingPlayerScreen(
                        bookId = bookId,
                        bookRepository = bookRepository,
                        settingsRepository = settingsRepository,
                        stateMachine = stateMachine,
                        prefetchPipeline = prefetchPipeline,
                        audioSynthesisManager = audioSynthesisManager,
                        onBack = { currentScreen = Screen.LIBRARY },
                        onNavigateToSettings = { currentScreen = Screen.SETTINGS },
                        onNavigateToErrors = { currentScreen = Screen.ERROR_HISTORY }
                    )
                } else {
                    currentScreen = Screen.LIBRARY
                }
            }

            Screen.SETTINGS -> {
                SettingsScreen(
                    settingsRepository = settingsRepository,
                    audioSynthesisManager = audioSynthesisManager,
                    onBack = {
                        currentScreen = if (selectedBookId != null) Screen.PLAYER else Screen.LIBRARY
                    }
                )
            }

            Screen.ERROR_HISTORY -> {
                ErrorHistoryScreen(
                    errorLogRepository = errorLogRepository,
                    onBack = {
                        currentScreen = if (selectedBookId != null) Screen.PLAYER else Screen.LIBRARY
                    }
                )
            }
        }

        // Global top floating error banner with instant copy button and 7-day history shortcut
        FloatingErrorBanner(
            onNavigateToHistory = { currentScreen = Screen.ERROR_HISTORY },
            modifier = Modifier.align(Alignment.TopCenter)
        )
    }
}
