package com.example.auto_dead_link_remover.ui.main

import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation3.runtime.NavKey
import com.example.auto_dead_link_remover.LinkCheckerService

import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main configuration screen optimized for Android TV with D-pad navigation.
 *
 * Features:
 * - Auto-detects device IP addresses for LAN access
 * - Live scan speed (links/sec) and ETA display
 * - Stop service button
 * - Smart retry toggle
 * - Playlist file size display
 * - Scan duration history
 */
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(LinkCheckerService.PREFS_NAME, Context.MODE_PRIVATE) }

    // --- Form state ---
    var sourceType by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_SOURCE_TYPE, "M3U") ?: "M3U") }
    var url by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_PLAYLIST_URL, "") ?: "") }
    var xtreamServer by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_XTREAM_SERVER, "") ?: "") }
    var xtreamUser by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_XTREAM_USER, "") ?: "") }
    var xtreamPass by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_XTREAM_PASS, "") ?: "") }
    var macServer by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_MAC_SERVER, "") ?: "") }
    var macAddress by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_MAC_ADDRESS, "") ?: "") }

    var intervalValue by remember { mutableStateOf(prefs.getLong(LinkCheckerService.KEY_INTERVAL_VALUE, 1L).toString()) }
    var intervalUnit by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_INTERVAL_UNIT, "HOURS") ?: "HOURS") }
    var timeoutSeconds by remember { mutableStateOf(prefs.getLong(LinkCheckerService.KEY_TIMEOUT_SECONDS, 5L).toString()) }
    var concurrentLinks by remember { mutableStateOf(prefs.getInt(LinkCheckerService.KEY_CONCURRENT_LINKS, 20).toString()) }
    var retryFailed by remember { mutableStateOf(prefs.getBoolean("retry_failed", true)) }

    // --- Dashboard state (polled every 2 seconds) ---
    var lastCheckTime by remember { mutableStateOf(prefs.getLong("last_check_time", 0L)) }
    var totalLinks by remember { mutableStateOf(prefs.getInt("total_links", 0)) }
    var aliveLinks by remember { mutableStateOf(prefs.getInt("alive_links", 0)) }
    var deadLinks by remember { mutableStateOf(prefs.getInt("dead_links", 0)) }
    var scanInProgress by remember { mutableStateOf(prefs.getBoolean("scan_in_progress", false)) }
    var serviceRunning by remember { mutableStateOf(prefs.getBoolean("service_running", false)) }
    var scanSpeed by remember { mutableStateOf(prefs.getFloat("scan_speed", 0f)) }
    var scanEta by remember { mutableStateOf(prefs.getString("scan_eta", "") ?: "") }
    var lastScanDuration by remember { mutableStateOf(prefs.getLong("last_scan_duration", 0L)) }
    var playlistFileSize by remember { mutableStateOf(prefs.getLong("playlist_file_size", 0L)) }

    // Auto-detect device IPs
    val deviceIps = remember { getDeviceIpAddresses() }

    // Poll dashboard values every 2 seconds
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            lastCheckTime = prefs.getLong("last_check_time", 0L)
            totalLinks = prefs.getInt("total_links", 0)
            aliveLinks = prefs.getInt("alive_links", 0)
            deadLinks = prefs.getInt("dead_links", 0)
            scanInProgress = prefs.getBoolean("scan_in_progress", false)
            serviceRunning = prefs.getBoolean("service_running", false)
            scanSpeed = prefs.getFloat("scan_speed", 0f)
            scanEta = prefs.getString("scan_eta", "") ?: ""
            lastScanDuration = prefs.getLong("last_scan_duration", 0L)
            playlistFileSize = prefs.getLong("playlist_file_size", 0L)
        }
    }

    val startService = remember(context) {
        { intent: Intent ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Title
        item(key = "title") {
            Text(
                text = "⚡ Auto Dead Link Remover",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            // Service status badge
            Row(
                modifier = Modifier.padding(bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val statusColor = if (serviceRunning) androidx.compose.ui.graphics.Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
                val statusText = if (serviceRunning) "● Service Running" else "○ Service Stopped"
                Text(
                    text = statusText,
                    color = statusColor,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // Source type selector
        item(key = "source_selector") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                listOf("M3U", "XTREAM", "MAC").forEach { type ->
                    val isSelected = sourceType == type
                    Button(
                        onClick = { sourceType = type },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                        ),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(horizontal = 4.dp)
                    ) {
                        Text(
                            when (type) {
                                "M3U" -> "M3U / M3U8"
                                "XTREAM" -> "Xtream Codes"
                                else -> "MAC / Stalker"
                            }
                        )
                    }
                }
            }
        }

        // Source configuration card
        item(key = "source_config") {
            Card(
                modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    when (sourceType) {
                        "M3U" -> {
                            OutlinedTextField(
                                value = url,
                                onValueChange = { url = it },
                                label = { Text("IPTV Playlist URLs (one per line or comma separated)") },
                                minLines = 3,
                                maxLines = 5,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        "XTREAM" -> {
                            OutlinedTextField(
                                value = xtreamServer,
                                onValueChange = { xtreamServer = it },
                                label = { Text("Server URL (http://example.com:8080)") },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = xtreamUser,
                                onValueChange = { xtreamUser = it },
                                label = { Text("Username") },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = xtreamPass,
                                onValueChange = { xtreamPass = it },
                                label = { Text("Password") },
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        "MAC" -> {
                            OutlinedTextField(
                                value = macServer,
                                onValueChange = { macServer = it },
                                label = { Text("Portal URL (http://example.com:8080)") },
                                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                            )
                            OutlinedTextField(
                                value = macAddress,
                                onValueChange = { macAddress = it },
                                label = { Text("MAC Address (00:1A:79:...)") },
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }
            }
        }

        // Settings
        item(key = "settings") {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = intervalValue,
                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) intervalValue = it },
                    label = { Text("Update Interval") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(16.dp))

                Row {
                    listOf("MINUTES" to "Min", "HOURS" to "Hr").forEach { (unit, label) ->
                        Button(
                            onClick = { intervalUnit = unit },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (intervalUnit == unit) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                                contentColor = if (intervalUnit == unit) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                            ),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(label)
                        }
                        if (unit == "MINUTES") Spacer(modifier = Modifier.width(8.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = timeoutSeconds,
                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) timeoutSeconds = it },
                    label = { Text("Timeout (sec)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )

                Spacer(modifier = Modifier.width(16.dp))

                OutlinedTextField(
                    value = concurrentLinks,
                    onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) concurrentLinks = it },
                    label = { Text("Max Parallel Scans") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Smart Retry toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🔁 Smart Retry (retry failed links with GET)",
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = retryFailed,
                    onCheckedChange = { retryFailed = it }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }

        // Action buttons
        item(key = "actions") {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                Button(
                    onClick = {
                        val finalInterval = intervalValue.toLongOrNull() ?: 1L
                        val finalTimeout = timeoutSeconds.toLongOrNull() ?: 5L
                        val finalConcurrent = (concurrentLinks.toIntOrNull() ?: 20).coerceIn(1, 200)
                        prefs.edit()
                            .putString(LinkCheckerService.KEY_SOURCE_TYPE, sourceType)
                            .putString(LinkCheckerService.KEY_PLAYLIST_URL, url)
                            .putString(LinkCheckerService.KEY_XTREAM_SERVER, xtreamServer)
                            .putString(LinkCheckerService.KEY_XTREAM_USER, xtreamUser)
                            .putString(LinkCheckerService.KEY_XTREAM_PASS, xtreamPass)
                            .putString(LinkCheckerService.KEY_MAC_SERVER, macServer)
                            .putString(LinkCheckerService.KEY_MAC_ADDRESS, macAddress)
                            .putLong(LinkCheckerService.KEY_INTERVAL_VALUE, finalInterval)
                            .putString(LinkCheckerService.KEY_INTERVAL_UNIT, intervalUnit)
                            .putLong(LinkCheckerService.KEY_TIMEOUT_SECONDS, finalTimeout)
                            .putInt(LinkCheckerService.KEY_CONCURRENT_LINKS, finalConcurrent)
                            .putBoolean("retry_failed", retryFailed)
                            .apply()

                        startService(Intent(context, LinkCheckerService::class.java))
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.padding(8.dp)
                ) {
                    Text("💾 Save & Start")
                }

                if (serviceRunning) {
                    Button(
                        onClick = {
                            val serviceIntent = Intent(context, LinkCheckerService::class.java).apply {
                                action = LinkCheckerService.ACTION_FORCE_REFRESH
                            }
                            startService(serviceIntent)
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                    ) {
                        Text("🔄 Scan Now")
                    }

                    Button(
                        onClick = {
                            val serviceIntent = Intent(context, LinkCheckerService::class.java).apply {
                                action = LinkCheckerService.ACTION_STOP_SERVICE
                            }
                            startService(serviceIntent)
                            serviceRunning = false
                        },
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.padding(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                    ) {
                        Text("⏹ Stop Service")
                    }
                }
            }
        }

        // Dashboard
        item(key = "dashboard") {
            if (serviceRunning || totalLinks > 0 || lastCheckTime > 0L) {
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        // Header with scan indicator
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "📊 Dashboard",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                            if (scanInProgress) {
                                Spacer(modifier = Modifier.width(12.dp))
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    "Scanning...",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(12.dp))

                        // Progress section
                        val processed = aliveLinks + deadLinks
                        if (scanInProgress && totalLinks > 0) {
                            val progress = processed.toFloat() / totalLinks.toFloat()
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    "$processed / $totalLinks (${(progress * 100).toInt()}%)",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    "⚡ ${String.format("%.1f", scanSpeed)} links/sec",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                            if (scanEta.isNotEmpty()) {
                                Text(
                                    "⏱ $scanEta",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }

                        // Stats
                        val dateStr = if (lastCheckTime > 0) {
                            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastCheckTime))
                        } else "Never"

                        Text("🕐 Last Checked: $dateStr")
                        Spacer(modifier = Modifier.height(4.dp))

                        if (lastScanDuration > 0 && !scanInProgress) {
                            val durationSec = lastScanDuration / 1000
                            val durationStr = when {
                                durationSec < 60 -> "${durationSec}s"
                                durationSec < 3600 -> "${durationSec / 60}m ${durationSec % 60}s"
                                else -> "${durationSec / 3600}h ${(durationSec % 3600) / 60}m"
                            }
                            Text("⏱ Last Scan Duration: $durationStr")
                            Spacer(modifier = Modifier.height(4.dp))
                        }

                        Text("📋 Total Links Found: $totalLinks")
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "✅ Alive Links: $aliveLinks",
                            color = androidx.compose.ui.graphics.Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "❌ Dead Links Removed: $deadLinks",
                            color = MaterialTheme.colorScheme.error
                        )

                        if (playlistFileSize > 0 && !scanInProgress) {
                            Spacer(modifier = Modifier.height(4.dp))
                            val sizeStr = when {
                                playlistFileSize < 1024 -> "${playlistFileSize} B"
                                playlistFileSize < 1024 * 1024 -> "${playlistFileSize / 1024} KB"
                                else -> "${String.format("%.1f", playlistFileSize / (1024.0 * 1024.0))} MB"
                            }
                            Text("📁 Clean Playlist Size: $sizeStr")
                        }

                        if (!scanInProgress && scanSpeed > 0) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Text("⚡ Avg Speed: ${String.format("%.1f", scanSpeed)} links/sec")
                        }
                    }
                }
            }
        }

        // Network Access card — shows actual device IPs
        item(key = "network") {
            if (serviceRunning || lastCheckTime > 0L) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            "🌐 Network Access",
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium
                        )
                        Spacer(modifier = Modifier.height(12.dp))

                        Text(
                            "Point your IPTV Player (TiviMate, etc.) to any of these URLs:",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(modifier = Modifier.height(8.dp))

                        // Localhost (same device)
                        Text(
                            text = "📱 This device: http://localhost:8080/playlist.m3u",
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold
                        )

                        // LAN IPs (other devices on same network)
                        if (deviceIps.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "From other devices on your network:",
                                style = MaterialTheme.typography.bodySmall
                            )
                            for (ip in deviceIps) {
                                Text(
                                    text = "🖥 http://$ip:8080/playlist.m3u",
                                    color = MaterialTheme.colorScheme.primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "💡 Health check: http://localhost:8080/status",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Detects all IPv4 addresses on the device's network interfaces.
 * Used to show the user which LAN IPs can be used to access the playlist
 * from other devices (phones, other TVs, laptops).
 */
private fun getDeviceIpAddresses(): List<String> {
    val ips = mutableListOf<String>()
    try {
        val interfaces = NetworkInterface.getNetworkInterfaces() ?: return ips
        for (networkInterface in interfaces) {
            if (networkInterface.isLoopback || !networkInterface.isUp) continue
            for (address in networkInterface.inetAddresses) {
                if (address is Inet4Address && !address.isLoopbackAddress) {
                    ips.add(address.hostAddress ?: continue)
                }
            }
        }
    } catch (_: Exception) {
        // Silently fail — this is a nice-to-have feature
    }
    return ips
}
