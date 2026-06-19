package com.example.auto_dead_link_remover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
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
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))

        PlaylistManager.resetClient()

        // Acquire a partial wake lock to prevent the TV from sleeping during scans
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "AutoDeadLinkRemover::ScanWakeLock"
        ).apply {
            setReferenceCounted(false)
        }

        // Start NanoHTTPD Server
        try {
            localServer = LocalIptvServer(8080, playlistManager.getCleanPlaylistFile())
            localServer?.start()
            Log.i(TAG, "Local IPTV Server started on port 8080")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start local server", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Handle stop action
        if (intent?.action == ACTION_STOP_SERVICE) {
            Log.i(TAG, "Stop action received, stopping service")
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

        // Mark service as running
        prefs.edit().putBoolean("service_running", true).apply()

        PlaylistManager.resetClient()

        if (intent?.action == ACTION_FORCE_REFRESH) {
            serviceScope.launch {
                Log.i(TAG, "Force refreshing playlist")
                acquireWakeLock()
                updateNotification("Scanning links...")
                playlistManager.processPlaylist(timeoutSeconds)
                updateNotification("Running on port 8080")
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
                Log.i(TAG, "Starting scheduled link check cycle")
                acquireWakeLock()
                updateNotification("Scanning links...")
                playlistManager.processPlaylist(timeoutSeconds)
                releaseWakeLock()
                updateNotification("Running on port 8080 • Next scan in ${intervalValue}${if (intervalUnit == "MINUTES") "m" else "h"}")
                delay(delayMs)
            }
        }

        return START_STICKY
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
        
        Log.i(TAG, "Service destroyed, server stopped, client released")
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun acquireWakeLock() {
        wakeLock?.let {
            if (!it.isHeld) {
                it.acquire(30 * 60 * 1000L) // Max 30 minutes per scan
                Log.d(TAG, "Wake lock acquired")
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
                Log.d(TAG, "Wake lock released")
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Auto Dead Link Remover Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Background service for IPTV playlist cleaning"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(text: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Dead Link Remover")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, createNotification(text))
    }
}
