package com.example.auto_dead_link_remover.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

class PlaylistManager(private val context: Context) {

    suspend fun processPlaylist(sourceUrl: String, timeoutSeconds: Long) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()

        try {
            Log.d("PlaylistManager", "Downloading playlist from: $sourceUrl")
            val request = Request.Builder().url(sourceUrl).build()
            val response = client.newCall(request).execute()
            
            if (!response.isSuccessful) {
                Log.e("PlaylistManager", "Failed to download playlist: ${response.code}")
                return@withContext
            }

            val body = response.body?.string() ?: return@withContext
            val lines = body.lines()

            val cleanLines = mutableListOf<String>()
            var currentExtInf: String? = null
            
            var totalLinks = 0
            var aliveLinks = 0
            var deadLinks = 0

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isEmpty()) continue

                if (trimmed.startsWith("#EXTM3U")) {
                    cleanLines.add(trimmed)
                } else if (trimmed.startsWith("#EXTINF")) {
                    currentExtInf = trimmed
                } else if (!trimmed.startsWith("#")) {
                    // It's a URL
                    totalLinks++
                    val url = trimmed
                    if (isLinkAlive(url, client)) {
                        aliveLinks++
                        if (currentExtInf != null) {
                            cleanLines.add(currentExtInf)
                        }
                        cleanLines.add(url)
                    } else {
                        deadLinks++
                        Log.d("PlaylistManager", "Dead link removed: $url")
                    }
                    currentExtInf = null // Reset for next item
                } else {
                    // Other comments/directives
                    cleanLines.add(trimmed)
                }
            }

            val cleanPlaylistStr = cleanLines.joinToString("\n")
            
            val outFile = getCleanPlaylistFile()
            outFile.writeText(cleanPlaylistStr)
            Log.d("PlaylistManager", "Clean playlist saved to ${outFile.absolutePath}")

            prefs.edit()
                .putLong("last_check_time", System.currentTimeMillis())
                .putInt("total_links", totalLinks)
                .putInt("alive_links", aliveLinks)
                .putInt("dead_links", deadLinks)
                .apply()

        } catch (e: Exception) {
            Log.e("PlaylistManager", "Error processing playlist", e)
        }
    }

    private fun isLinkAlive(url: String, client: OkHttpClient): Boolean {
        return try {
            val request = Request.Builder().url(url).head().build() // Use HEAD request to save bandwidth
            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            // 200 OK, 302 Found, etc.
            code in 200..399 || code == 405 // 405 Method Not Allowed implies server is there but doesn't like HEAD
        } catch (e: Exception) {
            false
        }
    }

    fun getCleanPlaylistFile(): File {
        return File(context.filesDir, "clean_playlist.m3u")
    }
}
