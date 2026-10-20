package com.example.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.example.MainActivity
import com.example.R
import com.example.logging.AppLogger

class AudiobookMediaService : MediaSessionService() {

    private var player: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    companion object {
        const val CHANNEL_ID = "drive_audiobook_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_PLAY = "com.example.service.PLAY"
        const val ACTION_PAUSE = "com.example.service.PAUSE"
        const val ACTION_STOP = "com.example.service.STOP"

        var currentServiceInstance: AudiobookMediaService? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        currentServiceInstance = this

        createNotificationChannel()

        val p = AudioPlayerManager.init(this)
        player = p

        val sessionActivityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        mediaSession = MediaSession.Builder(this, p)
            .setSessionActivity(sessionActivityPendingIntent)
            .build()

        startForeground(NOTIFICATION_ID, buildNotification("车载听书", "播放服务已就绪", false))
        AppLogger.logInfo("AudiobookMediaService", "AudiobookMediaService initialized and bound with AudioPlayerManager")
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? {
        return mediaSession
    }

    fun getPlayer(): ExoPlayer? = player ?: AudioPlayerManager.getPlayer()

    fun playAudioFile(filePath: String, startPositionMs: Long = 0L) {
        AudioPlayerManager.playAudioFile(filePath, startPositionMs)
    }

    fun pause() {
        AudioPlayerManager.pause()
    }

    fun resume() {
        AudioPlayerManager.resume()
    }

    fun seekTo(positionMs: Long) {
        AudioPlayerManager.seekTo(positionMs)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "车载听书播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "车载随身听书伴读后台播放与控制服务"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    fun updateNotification(title: String = "车载听书", content: String = "正在收听...") {
        try {
            val isPlaying = player?.isPlaying == true || AudioPlayerManager.isPlaying.value
            val notification = buildNotification(title, content, isPlaying)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.notify(NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            // Ignore notification update errors
        }
    }

    private fun buildNotification(title: String, content: String, isPlaying: Boolean): android.app.Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(content)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        currentServiceInstance = null
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
        AppLogger.logInfo("AudiobookMediaService", "AudiobookMediaService destroyed")
    }
}
