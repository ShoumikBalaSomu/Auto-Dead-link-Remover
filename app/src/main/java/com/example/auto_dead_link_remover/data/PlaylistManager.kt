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
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileWriter
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance playlist manager optimized for low-memory Android TV devices (2GB RAM).
 *
 * Key design decisions:
 * - Singleton OkHttpClient with in-memory DNS cache to avoid repeated DNS lookups.
 * - Channel-based worker pool: caps actual concurrent coroutines to N.
 * - Stream-to-disk output: writes alive links directly via BufferedWriter.
 * - Throttled progress updates with scan speed (links/sec) and ETA.
 * - Smart retry: links that fail on HEAD get a single GET retry before marking dead.
 */
class PlaylistManager(private val context: Context) {

    companion object {
        private const val TAG = "PlaylistManager"

        @Volatile
        private var httpClient: OkHttpClient? = null
        private val clientLock = Any()

        /**
         * In-memory DNS cache — avoids repeated system DNS lookups for the same hosts.
         * Critical for IPTV playlists where thousands of links share a handful of domains.
         * Entries expire after 10 minutes.
         */
        private val dnsCache = ConcurrentHashMap<String, Pair<List<InetAddress>, Long>>()
        private const val DNS_TTL_MS = 10 * 60 * 1000L // 10 minutes

        private val cachedDns = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> {
                val now = System.currentTimeMillis()
                val cached = dnsCache[hostname]
                if (cached != null && (now - cached.second) < DNS_TTL_MS) {
                    return cached.first
                }
                val addresses = Dns.SYSTEM.lookup(hostname)
                dnsCache[hostname] = addresses to now
                return addresses
            }
        }

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
                    .dns(cachedDns)
                    .retryOnConnectionFailure(false)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()
                    .also { httpClient = it }
            }
        }

        fun resetClient() {
            synchronized(clientLock) {
                httpClient?.dispatcher?.executorService?.shutdown()
                httpClient?.connectionPool?.evictAll()
                httpClient = null
                dnsCache.clear()
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
        val retryEnabled = prefs.getBoolean("retry_failed", true)
        val client = getClient(timeoutSeconds, concurrentLinks)

        val sourceType = prefs.getString("source_type", "M3U") ?: "M3U"

        val headerLines = mutableListOf<String>()
        val itemsToTest = ArrayList<PlaylistItem>(4096)

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
            val scanStartTime = AtomicLong(System.currentTimeMillis())

            Log.i(TAG, "Starting scan: $totalLinks links, concurrency=$concurrentLinks, timeout=${timeoutSeconds}s, retry=$retryEnabled")

            prefs.edit()
                .putInt("total_links", totalLinks)
                .putInt("alive_links", 0)
                .putInt("dead_links", 0)
                .putBoolean("scan_in_progress", true)
                .putLong("scan_start_time", scanStartTime.get())
                .putFloat("scan_speed", 0f)
                .putString("scan_eta", "Calculating...")
                .apply()

            val aliveFlags = BooleanArray(totalLinks)
            val checkedUrls = ConcurrentHashMap<String, Boolean>()

            coroutineScope {
                val channel = Channel<IndexedValue<PlaylistItem>>(capacity = Channel.BUFFERED)

                // Throttled progress reporter with speed & ETA
                val progressJob = launch {
                    while (isActive) {
                        delay(2000)
                        val processed = aliveCount.get() + deadCount.get()
                        val elapsed = (System.currentTimeMillis() - scanStartTime.get()) / 1000.0
                        val speed = if (elapsed > 0) processed / elapsed else 0.0
                        val remaining = totalLinks - processed
                        val etaSeconds = if (speed > 0) (remaining / speed).toLong() else 0L

                        val etaStr = when {
                            processed >= totalLinks -> "Complete"
                            etaSeconds < 60 -> "${etaSeconds}s remaining"
                            etaSeconds < 3600 -> "${etaSeconds / 60}m ${etaSeconds % 60}s remaining"
                            else -> "${etaSeconds / 3600}h ${(etaSeconds % 3600) / 60}m remaining"
                        }

                        prefs.edit()
                            .putInt("alive_links", aliveCount.get())
                            .putInt("dead_links", deadCount.get())
                            .putFloat("scan_speed", speed.toFloat())
                            .putString("scan_eta", etaStr)
                            .apply()
                    }
                }

                // Launch exactly N worker coroutines
                val workers = List(concurrentLinks) {
                    launch {
                        for (indexed in channel) {
                            val item = indexed.value
                            val index = indexed.index
                            val url = item.url
                            
                            val cachedAlive = checkedUrls[url]
                            
                            val isAlive = if (cachedAlive != null) {
                                cachedAlive
                            } else {
                                var alive = isLinkAlive(url, client)
                                if (!alive && retryEnabled) {
                                    alive = isLinkAliveGet(url, client)
                                }
                                checkedUrls[url] = alive
                                alive
                            }

                            if (isAlive) {
                                aliveCount.incrementAndGet()
                                aliveFlags[index] = true
                            } else {
                                deadCount.incrementAndGet()
                            }
                        }
                    }
                }

                // Producer
                launch {
                    for ((index, item) in itemsToTest.withIndex()) {
                        channel.send(IndexedValue(index, item))
                    }
                    channel.close()
                }

                workers.forEach { it.join() }
                progressJob.cancel()
            }

            // Write clean playlist to disk
            val tempFile = File(context.filesDir, "clean_playlist.m3u.tmp")
            val outFile = getCleanPlaylistFile()

            BufferedWriter(FileWriter(tempFile), 8192).use { writer ->
                for (header in headerLines) {
                    writer.write(header)
                    writer.newLine()
                }
                
                // Write items sequentially, preserving exact original M3U order
                for (i in itemsToTest.indices) {
                    if (aliveFlags[i]) {
                        val item = itemsToTest[i]
                        item.extInf?.let {
                            writer.write(it)
                            writer.newLine()
                        }
                        writer.write(item.url)
                        writer.newLine()
                    }
                }
            }

            tempFile.renameTo(outFile)

            // Final stats
            val finalAlive = aliveCount.get()
            val finalDead = deadCount.get()
            val elapsed = (System.currentTimeMillis() - scanStartTime.get()) / 1000.0
            val finalSpeed = if (elapsed > 0) totalLinks / elapsed else 0.0

            prefs.edit()
                .putInt("alive_links", finalAlive)
                .putInt("dead_links", finalDead)
                .putLong("last_check_time", System.currentTimeMillis())
                .putBoolean("scan_in_progress", false)
                .putFloat("scan_speed", finalSpeed.toFloat())
                .putString("scan_eta", "Complete")
                .putLong("last_scan_duration", (elapsed * 1000).toLong())
                .putLong("playlist_file_size", outFile.length())
                .apply()

            Log.i(TAG, "Scan complete: $finalAlive alive, $finalDead dead, ${elapsed.toLong()}s elapsed, ${String.format("%.1f", finalSpeed)} links/sec")

            itemsToTest.clear()

        } catch (e: Exception) {
            Log.e(TAG, "Error processing playlist", e)
            prefs.edit()
                .putBoolean("scan_in_progress", false)
                .putString("scan_eta", "Error: ${e.message}")
                .apply()
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

    /** Fast HEAD-based link check */
    private fun isLinkAlive(url: String, client: OkHttpClient): Boolean {
        return try {
            val request = Request.Builder().url(url).head().build()
            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            code in 200..399 || code == 405
        } catch (_: Exception) {
            false
        }
    }

    /** Fallback GET-based check for servers that reject HEAD requests */
    private fun isLinkAliveGet(url: String, client: OkHttpClient): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("Range", "bytes=0-0") // Request only 1 byte to save bandwidth
                .build()
            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            code in 200..399 || code == 405 || code == 416 // 416 = Range Not Satisfiable = server is alive
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
