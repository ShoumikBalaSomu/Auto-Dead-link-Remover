package com.example.auto_dead_link_remover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.auto_dead_link_remover.data.LocalIptvServer
import com.example.auto_dead_link_remover.data.PlaylistManager
import kotlinx.coroutines.*

class LinkCheckerService : Service() {

    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private lateinit var playlistManager: PlaylistManager
    private var localServer: LocalIptvServer? = null
    private var checkJob: Job? = null
    private var notificationProgressJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "LinkCheckerService"
        const val CHANNEL_ID = "LinkCheckerChannel"
        const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "iptv_prefs"
        const val KEY_SOURCE_TYPE = "source_type"
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_XTREAM_SERVER = "xtream_server"
        const val KEY_XTREAM_USER = "xtream_user"
        const val KEY_XTREAM_PASS = "xtream_pass"
        const val KEY_MAC_SERVER = "mac_server"
        const val KEY_MAC_ADDRESS = "mac_address"
        const val KEY_INTERVAL_VALUE = "interval_value"
        const val KEY_INTERVAL_UNIT = "interval_unit"
        const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        const val KEY_CONCURRENT_LINKS = "concurrent_links"
        
        const val ACTION_FORCE_REFRESH = "com.example.auto_dead_link_remover.FORCE_REFRESH"
        const val ACTION_STOP_SERVICE = "com.example.auto_dead_link_remover.STOP_SERVICE"
    }

    override fun onCreate() {
        super.onCreate()
        playlistManager = PlaylistManager(this)
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing...", indeterminate = true))

        PlaylistManager.resetClient()

        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AutoDeadLinkRemover::ScanWakeLock"
        ).apply { setReferenceCounted(false) }

        try {
            localServer = LocalIptvServer(8080, playlistManager.getCleanPlaylistFile())
            localServer?.start()
            Log.i(TAG, "Local IPTV Server started on port 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local server", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.i(TAG, "Stop action received")
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit()
                .putBoolean("service_running", false)
                .putBoolean("scan_in_progress", false)
                .apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalValue = prefs.getLong(KEY_INTERVAL_VALUE, 1L)
        val intervalUnit = prefs.getString(KEY_INTERVAL_UNIT, "HOURS")
        val timeoutSeconds = prefs.getLong(KEY_TIMEOUT_SECONDS, 5L)

        prefs.edit().putBoolean("service_running", true).apply()
        PlaylistManager.resetClient()

        if (intent?.action == ACTION_FORCE_REFRESH) {
            serviceScope.launch {
                acquireWakeLock()
                startNotificationProgress()
                playlistManager.processPlaylist(timeoutSeconds)
                stopNotificationProgress()
                updateNotification("✅ Scan complete • Serving on :8080")
                releaseWakeLock()
            }
            return START_STICKY
        }

        val delayMs = if (intervalUnit == "MINUTES") {
            intervalValue * 60 * 1000
        } else {
            intervalValue * 60 * 60 * 1000
        }

        checkJob?.cancel()

        checkJob = serviceScope.launch {
            while (isActive) {
                acquireWakeLock()
                startNotificationProgress()
                playlistManager.processPlaylist(timeoutSeconds)
                stopNotificationProgress()
                updateNotification("✅ Running • Next scan in ${intervalValue}${if (intervalUnit == "MINUTES") "m" else "h"}")
                releaseWakeLock()
                delay(delayMs)
            }
        }

        return START_STICKY
    }

    /**
     * Starts a coroutine that updates the notification with real-time scan progress.
     * Shows a progress bar in the phone's notification shade.
     */
    private fun startNotificationProgress() {
        stopNotificationProgress()
        notificationProgressJob = serviceScope.launch {
            while (isActive) {
                delay(3000)
                val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                val total = prefs.getInt("total_links", 0)
                val alive = prefs.getInt("alive_links", 0)
                val dead = prefs.getInt("dead_links", 0)
                val speed = prefs.getFloat("scan_speed", 0f)
                val scanning = prefs.getBoolean("scan_in_progress", false)

                if (!scanning) break

                val processed = alive + dead
                if (total > 0) {
                    val percent = (processed * 100) / total
                    updateNotificationWithProgress(
                        "Scanning: $processed/$total ($percent%)",
                        "⚡ ${String.format("%.1f", speed)}/sec • ✅$alive ❌$dead",
                        processed,
                        total
                    )
                } else {
                    updateNotification("Downloading playlist...")
                }
            }
        }
    }

    private fun stopNotificationProgress() {
        notificationProgressJob?.cancel()
        notificationProgressJob = null
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        localServer?.stop()
        releaseWakeLock()
        PlaylistManager.resetClient()
        
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit()
            .putBoolean("service_running", false)
            .putBoolean("scan_in_progress", false)
            .apply()
        
        Log.i(TAG, "Service destroyed")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        wakeLock?.let { if (!it.isHeld) it.acquire(30 * 60 * 1000L) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Dead Link Remover",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for IPTV playlist cleaning"
                setShowBadge(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(serviceChannel)
        }
    }

    private fun getBaseNotificationBuilder(text: String): NotificationCompat.Builder {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("⚡ Dead Link Remover")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(pendingIntent)
    }

    private fun createNotification(text: String, indeterminate: Boolean = false): Notification {
        val builder = getBaseNotificationBuilder(text)
        if (indeterminate) {
            builder.setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            ?.notify(NOTIFICATION_ID, createNotification(text))
    }

    private fun updateNotificationWithProgress(title: String, subtitle: String, current: Int, total: Int) {
        val notification = getBaseNotificationBuilder(title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subtitle))
            .setProgress(total, current, false)
            .build()
        getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
    }
}
