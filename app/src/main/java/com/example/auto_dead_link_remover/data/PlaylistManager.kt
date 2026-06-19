package com.example.auto_dead_link_remover.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class PlaylistManager(private val context: Context) {

    data class PlaylistItem(
        val extInf: String?,
        val url: String
    )

    suspend fun processPlaylist(timeoutSeconds: Long) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val client = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .connectionPool(ConnectionPool(50, 5, TimeUnit.MINUTES))
            .build()

        val sourceType = prefs.getString("source_type", "M3U") ?: "M3U"
        
        val cleanLines = mutableListOf<String>()
        val itemsToTest = mutableListOf<PlaylistItem>()

        try {
            if (sourceType == "M3U" || sourceType == "XTREAM") {
                val sourceUrl = if (sourceType == "XTREAM") {
                    val server = prefs.getString("xtream_server", "") ?: ""
                    val user = prefs.getString("xtream_user", "") ?: ""
                    val pass = prefs.getString("xtream_pass", "") ?: ""
                    var formattedServer = server
                    if (!formattedServer.startsWith("http://") && !formattedServer.startsWith("https://")) {
                        formattedServer = "http://$formattedServer"
                    }
                    if (formattedServer.endsWith("/")) {
                        formattedServer = formattedServer.dropLast(1)
                    }
                    "$formattedServer/get.php?username=$user&password=$pass&type=m3u_plus&output=ts"
                } else {
                    prefs.getString("playlist_url", "") ?: ""
                }

                if (sourceUrl.isEmpty() || sourceUrl.isBlank()) {
                    Log.w("PlaylistManager", "Source URL is empty")
                    return@withContext
                }

                Log.d("PlaylistManager", "Downloading playlist from: $sourceUrl")
                val request = Request.Builder().url(sourceUrl).build()
                val response = client.newCall(request).execute()
                
                if (!response.isSuccessful) {
                    Log.e("PlaylistManager", "Failed to download playlist: ${response.code}")
                    return@withContext
                }

                val body = response.body?.string() ?: return@withContext
                val lines = body.lines()

                var currentExtInf: String? = null

                for (line in lines) {
                    val trimmed = line.trim()
                    if (trimmed.isEmpty()) continue

                    if (trimmed.startsWith("#EXTM3U")) {
                        cleanLines.add(trimmed)
                    } else if (trimmed.startsWith("#EXTINF")) {
                        currentExtInf = trimmed
                    } else if (!trimmed.startsWith("#")) {
                        itemsToTest.add(PlaylistItem(currentExtInf, trimmed))
                        currentExtInf = null
                    } else {
                        cleanLines.add(trimmed)
                    }
                }
            } else if (sourceType == "MAC") {
                var server = prefs.getString("mac_server", "") ?: ""
                val mac = prefs.getString("mac_address", "") ?: ""
                if (!server.startsWith("http://") && !server.startsWith("https://")) {
                    server = "http://$server"
                }
                if (server.endsWith("/")) {
                    server = server.dropLast(1)
                }
                
                val handshakeUrl = "$server/portal.php?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
                val handshakeReq = Request.Builder()
                    .url(handshakeUrl)
                    .addHeader("Cookie", "mac=$mac")
                    .build()
                
                val handshakeResp = client.newCall(handshakeReq).execute()
                val handshakeBody = handshakeResp.body?.string() ?: ""
                
                var token = ""
                try {
                    val json = JSONObject(handshakeBody)
                    token = json.getJSONObject("js").getString("token")
                } catch (e: Exception) {
                    Log.e("PlaylistManager", "Failed to parse Stalker handshake", e)
                }
                
                if (token.isNotEmpty()) {
                    val channelsUrl = "$server/portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
                    val channelsReq = Request.Builder()
                        .url(channelsUrl)
                        .addHeader("Cookie", "mac=$mac")
                        .addHeader("Authorization", "Bearer $token")
                        .build()
                        
                    val channelsResp = client.newCall(channelsReq).execute()
                    val channelsBody = channelsResp.body?.string() ?: ""
                    
                    try {
                        val json = JSONObject(channelsBody)
                        val dataArray = json.optJSONArray("js") ?: json.getJSONObject("js").getJSONArray("data")
                        
                        cleanLines.add("#EXTM3U")
                        for (i in 0 until dataArray.length()) {
                            val channel = dataArray.getJSONObject(i)
                            val name = channel.optString("name", "Unknown Channel")
                            var cmd = channel.optString("cmd", "")
                            
                            if (cmd.startsWith("ffmpeg ")) {
                                cmd = cmd.substringAfter("ffmpeg ")
                            }
                            
                            if (cmd.isNotEmpty() && (cmd.startsWith("http") || cmd.startsWith("rtmp"))) {
                                itemsToTest.add(PlaylistItem("#EXTINF:-1,$name", cmd))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("PlaylistManager", "Failed to parse Stalker channels", e)
                    }
                }
            }

            val totalLinks = itemsToTest.size
            val aliveLinks = AtomicInteger(0)
            val deadLinks = AtomicInteger(0)
            
            prefs.edit()
                .putInt("total_links", totalLinks)
                .putInt("alive_links", 0)
                .putInt("dead_links", 0)
                .apply()

            val semaphore = Semaphore(50)
            
            val deferreds = itemsToTest.map { item ->
                async {
                    semaphore.withPermit {
                        val isAlive = isLinkAlive(item.url, client)
                        if (isAlive) {
                            aliveLinks.incrementAndGet()
                        } else {
                            deadLinks.incrementAndGet()
                            Log.d("PlaylistManager", "Dead link removed: ${item.url}")
                        }
                        
                        // Progressive UI update
                        val currentAlive = aliveLinks.get()
                        val currentDead = deadLinks.get()
                        val processed = currentAlive + currentDead
                        if (processed % 10 == 0 || processed == totalLinks) {
                            prefs.edit()
                                .putInt("alive_links", currentAlive)
                                .putInt("dead_links", currentDead)
                                .apply()
                        }
                        
                        item to isAlive
                    }
                }
            }

            val results = deferreds.awaitAll()
            
            // Assemble clean list maintaining order
            for ((item, isAlive) in results) {
                if (isAlive) {
                    item.extInf?.let { cleanLines.add(it) }
                    cleanLines.add(item.url)
                }
            }

            val cleanPlaylistStr = cleanLines.joinToString("\n")
            val outFile = getCleanPlaylistFile()
            outFile.writeText(cleanPlaylistStr)
            Log.d("PlaylistManager", "Clean playlist saved to ${outFile.absolutePath}")

            prefs.edit()
                .putLong("last_check_time", System.currentTimeMillis())
                .apply()

        } catch (e: Exception) {
            Log.e("PlaylistManager", "Error processing playlist", e)
        }
    }

    private fun isLinkAlive(url: String, client: OkHttpClient): Boolean {
        return try {
            val request = Request.Builder().url(url).head().build()
            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            code in 200..399 || code == 405
        } catch (e: Exception) {
            false
        }
    }

    fun getCleanPlaylistFile(): File {
        return File(context.filesDir, "clean_playlist.m3u")
    }
}
