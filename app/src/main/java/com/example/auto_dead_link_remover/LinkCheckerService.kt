package com.example.auto_dead_link_remover

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.auto_dead_link_remover.data.LocalIptvServer
import com.example.auto_dead_link_remover.data.PlaylistManager
import kotlinx.coroutines.*

class LinkCheckerService : Service() {

    // Use SupervisorJob so a single failed scan doesn't kill the service
    private val serviceJob = SupervisorJob()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private lateinit var playlistManager: PlaylistManager
    private var localServer: LocalIptvServer? = null
    private var checkJob: Job? = null

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
    }

    override fun onCreate() {
        super.onCreate()
        playlistManager = PlaylistManager(this)
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification("Initializing..."))

        // Reset the HTTP client so it picks up latest settings
        PlaylistManager.resetClient()

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
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalValue = prefs.getLong(KEY_INTERVAL_VALUE, 1L)
        val intervalUnit = prefs.getString(KEY_INTERVAL_UNIT, "HOURS")
        val timeoutSeconds = prefs.getLong(KEY_TIMEOUT_SECONDS, 5L)

        // Reset client to pick up any settings changes from the UI
        PlaylistManager.resetClient()

        if (intent?.action == ACTION_FORCE_REFRESH) {
            serviceScope.launch {
                Log.i(TAG, "Force refreshing playlist")
                updateNotification("Scanning links...")
                playlistManager.processPlaylist(timeoutSeconds)
                updateNotification("Running on port 8080")
            }
            return START_STICKY
        }

        val delayMs = if (intervalUnit == "MINUTES") {
            intervalValue * 60 * 1000
        } else {
            intervalValue * 60 * 60 * 1000
        }

        // Cancel any existing scan loop before starting a new one
        checkJob?.cancel()

        checkJob = serviceScope.launch {
            while (isActive) {
                Log.i(TAG, "Starting scheduled link check cycle")
                updateNotification("Scanning links...")
                playlistManager.processPlaylist(timeoutSeconds)
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
        PlaylistManager.resetClient()
        Log.i(TAG, "Service destroyed, server stopped, client released")
    }

    override fun onBind(intent: Intent?): IBinder? = null

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
