package com.example.auto_dead_link_remover.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * High-performance playlist manager optimized for low-memory Android TV devices (2GB RAM).
 *
 * Key design decisions:
 * - Singleton OkHttpClient: avoids connection pool leaks across scan cycles.
 * - Channel-based worker pool: caps actual concurrent coroutines to N instead of spawning
 *   thousands of Deferred objects.
 * - Stream-to-disk output: writes alive links directly to a temp file via BufferedWriter
 *   instead of accumulating them all in a List<String> and then joinToString.
 * - Throttled progress updates: SharedPreferences written at most once per 2 seconds
 *   to avoid triggering cascading Compose recompositions.
 */
class PlaylistManager(private val context: Context) {

    companion object {
        private const val TAG = "PlaylistManager"

        // Singleton HTTP client — reused across ALL scan cycles.
        // Connection pool and dispatcher are configured once.
        @Volatile
        private var httpClient: OkHttpClient? = null
        private val clientLock = Any()

        fun getClient(timeoutSeconds: Long, maxConcurrent: Int): OkHttpClient {
            return httpClient ?: synchronized(clientLock) {
                httpClient ?: OkHttpClient.Builder()
                    .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                    .connectionPool(ConnectionPool(maxConcurrent, 2, TimeUnit.MINUTES))
                    .dispatcher(Dispatcher().apply {
                        maxRequests = maxConcurrent
                        maxRequestsPerHost = maxConcurrent
                    })
                    .retryOnConnectionFailure(false) // Don't waste time retrying dead links
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                    .also { httpClient = it }
            }
        }

        /** Reconfigure client if user changes timeout/concurrency settings. */
        fun resetClient() {
            synchronized(clientLock) {
                httpClient?.dispatcher?.executorService?.shutdown()
                httpClient?.connectionPool?.evictAll()
                httpClient = null
            }
        }
    }

    data class PlaylistItem(
        val extInf: String?,
        val url: String
    )

    suspend fun processPlaylist(timeoutSeconds: Long) = withContext(Dispatchers.IO) {
        val prefs = context.getSharedPreferences("iptv_prefs", Context.MODE_PRIVATE)
        val concurrentLinks = prefs.getInt("concurrent_links", 20).coerceIn(1, 200)
        val client = getClient(timeoutSeconds, concurrentLinks)

        val sourceType = prefs.getString("source_type", "M3U") ?: "M3U"

        // Phase 1: Parse playlist into items to test.
        // Header lines (like #EXTM3U) go directly to the output file.
        val headerLines = mutableListOf<String>()
        val itemsToTest = ArrayList<PlaylistItem>(4096) // pre-size to avoid resizing

        try {
            when (sourceType) {
                "M3U", "XTREAM" -> parseM3uOrXtream(prefs, sourceType, client, headerLines, itemsToTest)
                "MAC" -> parseMacStalker(prefs, client, headerLines, itemsToTest)
            }

            if (itemsToTest.isEmpty()) {
                Log.w(TAG, "No items found to test")
                return@withContext
            }

            val totalLinks = itemsToTest.size
            val aliveCount = AtomicInteger(0)
            val deadCount = AtomicInteger(0)

            Log.i(TAG, "Starting scan: $totalLinks links, concurrency=$concurrentLinks, timeout=${timeoutSeconds}s")

            prefs.edit()
                .putInt("total_links", totalLinks)
                .putInt("alive_links", 0)
                .putInt("dead_links", 0)
                .putBoolean("scan_in_progress", true)
                .apply()

            // Phase 2: Check links using a bounded worker pool via Channel.
            // This is THE key optimization: instead of creating N coroutines with async{},
            // we create exactly `concurrentLinks` workers that pull from a shared channel.
            // Memory stays flat regardless of playlist size.
            val aliveItems = java.util.concurrent.ConcurrentLinkedQueue<PlaylistItem>()

            coroutineScope {
                val channel = Channel<PlaylistItem>(capacity = Channel.BUFFERED)

                // Throttled progress reporter — updates prefs at most every 2 seconds
                val progressJob = launch {
                    while (isActive) {
                        delay(2000)
                        prefs.edit()
                            .putInt("alive_links", aliveCount.get())
                            .putInt("dead_links", deadCount.get())
                            .apply()
                    }
                }

                // Launch exactly N worker coroutines
                val workers = List(concurrentLinks) {
                    launch {
                        for (item in channel) {
                            if (isLinkAlive(item.url, client)) {
                                aliveCount.incrementAndGet()
                                aliveItems.add(item)
                            } else {
                                deadCount.incrementAndGet()
                            }
                        }
                    }
                }

                // Feed items into the channel (this is the producer)
                launch {
                    for (item in itemsToTest) {
                        channel.send(item)
                    }
                    channel.close() // Signal workers to finish
                }

                // Wait for all workers to complete
                workers.forEach { it.join() }
                progressJob.cancel()
            }

            // Phase 3: Write clean playlist directly to disk via BufferedWriter.
            // No intermediate String or List — pure streaming I/O.
            val tempFile = File(context.filesDir, "clean_playlist.m3u.tmp")
            val outFile = getCleanPlaylistFile()

            BufferedWriter(FileWriter(tempFile), 8192).use { writer ->
                // Write header lines
                for (header in headerLines) {
                    writer.write(header)
                    writer.newLine()
                }

                // Write alive items — order doesn't matter for IPTV players
                for (item in aliveItems) {
                    item.extInf?.let {
                        writer.write(it)
                        writer.newLine()
                    }
                    writer.write(item.url)
                    writer.newLine()
                }
            }

            // Atomic rename: prevents serving a half-written file
            tempFile.renameTo(outFile)

            // Final progress update
            val finalAlive = aliveCount.get()
            val finalDead = deadCount.get()
            prefs.edit()
                .putInt("alive_links", finalAlive)
                .putInt("dead_links", finalDead)
                .putLong("last_check_time", System.currentTimeMillis())
                .putBoolean("scan_in_progress", false)
                .apply()

            Log.i(TAG, "Scan complete: $finalAlive alive, $finalDead dead out of $totalLinks total")

            // Help GC reclaim the items list immediately
            itemsToTest.clear()

        } catch (e: Exception) {
            Log.e(TAG, "Error processing playlist", e)
            prefs.edit().putBoolean("scan_in_progress", false).apply()
        }
    }

    private fun parseM3uOrXtream(
        prefs: SharedPreferences,
        sourceType: String,
        client: OkHttpClient,
        headerLines: MutableList<String>,
        itemsToTest: MutableList<PlaylistItem>
    ) {
        val sourceUrls = mutableListOf<String>()

        if (sourceType == "XTREAM") {
            val server = normalizeUrl(prefs.getString("xtream_server", "") ?: "")
            val user = prefs.getString("xtream_user", "") ?: ""
            val pass = prefs.getString("xtream_pass", "") ?: ""
            sourceUrls.add("$server/get.php?username=$user&password=$pass&type=m3u_plus&output=ts")
        } else {
            val rawUrls = prefs.getString("playlist_url", "") ?: ""
            sourceUrls.addAll(rawUrls.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() })
        }

        if (sourceUrls.isEmpty()) {
            Log.w(TAG, "Source URLs list is empty")
            return
        }

        var hasExtM3UHeader = false

        for (sourceUrl in sourceUrls) {
            Log.d(TAG, "Downloading playlist from: $sourceUrl")
            try {
                val request = Request.Builder().url(sourceUrl).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Log.e(TAG, "Failed to download playlist: ${response.code} from $sourceUrl")
                    response.close()
                    continue
                }

                // Stream-parse: never load the full body into memory
                response.body?.charStream()?.buffered(16384)?.useLines { lines ->
                    var currentExtInf: String? = null

                    for (line in lines) {
                        val trimmed = line.trim()
                        if (trimmed.isEmpty()) continue

                        when {
                            trimmed.startsWith("#EXTM3U") -> {
                                if (!hasExtM3UHeader) {
                                    headerLines.add(trimmed)
                                    hasExtM3UHeader = true
                                }
                            }
                            trimmed.startsWith("#EXTINF") -> {
                                currentExtInf = trimmed
                            }
                            !trimmed.startsWith("#") -> {
                                itemsToTest.add(PlaylistItem(currentExtInf, trimmed))
                                currentExtInf = null
                            }
                            else -> {
                                // Other comment/directive lines — keep unique ones
                                if (!headerLines.contains(trimmed)) {
                                    headerLines.add(trimmed)
                                }
                            }
                        }
                    }
                } ?: response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Error downloading from $sourceUrl", e)
            }
        }
    }

    private fun parseMacStalker(
        prefs: SharedPreferences,
        client: OkHttpClient,
        headerLines: MutableList<String>,
        itemsToTest: MutableList<PlaylistItem>
    ) {
        val server = normalizeUrl(prefs.getString("mac_server", "") ?: "")
        val mac = prefs.getString("mac_address", "") ?: ""

        // Step 1: Handshake to get auth token
        val handshakeUrl = "$server/portal.php?type=stb&action=handshake&token=&JsHttpRequest=1-xml"
        val handshakeReq = Request.Builder()
            .url(handshakeUrl)
            .addHeader("Cookie", "mac=$mac")
            .build()

        val handshakeResp = client.newCall(handshakeReq).execute()
        val handshakeBody = handshakeResp.body?.string() ?: ""
        handshakeResp.close()

        val token = try {
            JSONObject(handshakeBody).getJSONObject("js").getString("token")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse Stalker handshake", e)
            return
        }

        if (token.isEmpty()) return

        // Step 2: Fetch all channels
        val channelsUrl = "$server/portal.php?type=itv&action=get_all_channels&JsHttpRequest=1-xml"
        val channelsReq = Request.Builder()
            .url(channelsUrl)
            .addHeader("Cookie", "mac=$mac")
            .addHeader("Authorization", "Bearer $token")
            .build()

        val channelsResp = client.newCall(channelsReq).execute()
        val channelsBody = channelsResp.body?.string() ?: ""
        channelsResp.close()

        try {
            val json = JSONObject(channelsBody)
            val dataArray = json.optJSONArray("js") ?: json.getJSONObject("js").getJSONArray("data")

            headerLines.add("#EXTM3U")
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
            Log.e(TAG, "Failed to parse Stalker channels", e)
        }
    }

    /**
     * Fast link-alive check using HTTP HEAD with minimal overhead.
     * Closes the response body immediately to free the connection back to the pool.
     */
    private fun isLinkAlive(url: String, client: OkHttpClient): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            code in 200..399 || code == 405
        } catch (_: Exception) {
            false
        }
    }

    private fun normalizeUrl(url: String): String {
        var result = url
        if (!result.startsWith("http://") && !result.startsWith("https://")) {
            result = "http://$result"
        }
        if (result.endsWith("/")) {
            result = result.dropLast(1)
        }
        return result
    }

    fun getCleanPlaylistFile(): File {
        return File(context.filesDir, "clean_playlist.m3u")
    }
}
