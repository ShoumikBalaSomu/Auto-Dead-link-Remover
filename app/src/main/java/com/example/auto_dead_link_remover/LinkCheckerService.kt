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

    private val serviceJob = Job()
    private val serviceScope = CoroutineScope(Dispatchers.IO + serviceJob)
    
    private lateinit var playlistManager: PlaylistManager
    private var localServer: LocalIptvServer? = null

    private var checkJob: Job? = null

    companion object {
        const val CHANNEL_ID = "LinkCheckerChannel"
        const val NOTIFICATION_ID = 1
        const val PREFS_NAME = "iptv_prefs"
        const val KEY_SOURCE_TYPE = "source_type" // M3U, XTREAM, MAC
        const val KEY_PLAYLIST_URL = "playlist_url"
        const val KEY_XTREAM_SERVER = "xtream_server"
        const val KEY_XTREAM_USER = "xtream_user"
        const val KEY_XTREAM_PASS = "xtream_pass"
        const val KEY_MAC_SERVER = "mac_server"
        const val KEY_MAC_ADDRESS = "mac_address"
        const val KEY_INTERVAL_VALUE = "interval_value"
        const val KEY_INTERVAL_UNIT = "interval_unit"
        const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        const val ACTION_FORCE_REFRESH = "com.example.auto_dead_link_remover.FORCE_REFRESH"
    }

    override fun onCreate() {
        super.onCreate()
        playlistManager = PlaylistManager(this)
        
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        // Start NanoHTTPD Server
        try {
            localServer = LocalIptvServer(8080, playlistManager.getCleanPlaylistFile())
            localServer?.start()
            Log.d("LinkCheckerService", "Local IPTV Server started on port 8080")
        } catch (e: Exception) {
            Log.e("LinkCheckerService", "Failed to start local server", e)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val intervalValue = prefs.getLong(KEY_INTERVAL_VALUE, 1L)
        val intervalUnit = prefs.getString(KEY_INTERVAL_UNIT, "HOURS")
        val timeoutSeconds = prefs.getLong(KEY_TIMEOUT_SECONDS, 5L)

        if (intent?.action == ACTION_FORCE_REFRESH) {
            serviceScope.launch {
                Log.d("LinkCheckerService", "Force refreshing playlist")
                playlistManager.processPlaylist(timeoutSeconds)
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
                Log.d("LinkCheckerService", "Starting link check cycle")
                playlistManager.processPlaylist(timeoutSeconds)
                delay(delayMs)
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceJob.cancel()
        localServer?.stop()
        Log.d("LinkCheckerService", "Service destroyed and server stopped")
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(
                CHANNEL_ID,
                "Auto Dead Link Remover Service",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(serviceChannel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Auto Dead Link Remover")
            .setContentText("Running in background on port 8080")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .build()
    }
}
