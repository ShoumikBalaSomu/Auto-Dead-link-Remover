package com.example.auto_dead_link_remover.ui.main

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.widget.Toast
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation3.runtime.NavKey
import com.example.auto_dead_link_remover.LinkCheckerService
import com.example.auto_dead_link_remover.theme.*
import kotlinx.coroutines.delay
import java.net.Inet4Address
import java.net.NetworkInterface
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
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

    // Collapsible section states
    var settingsExpanded by remember { mutableStateOf(false) }
    var networkExpanded by remember { mutableStateOf(false) }

    val deviceIps = remember { getDeviceIpAddresses() }

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

    // Save settings helper
    val saveSettings: () -> Unit = remember(context) {
        {
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
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⚡", fontSize = 22.sp)
                        Spacer(Modifier.width(8.dp))
                        Text("Dead Link Remover", fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    // Service status pill
                    val statusColor by animateColorAsState(
                        if (serviceRunning) GreenAlive else RedDead,
                        label = "statusColor"
                    )
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = statusColor.copy(alpha = 0.15f),
                        modifier = Modifier.padding(end = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Animated pulsing dot
                            val alpha by rememberInfiniteTransition(label = "pulse").animateFloat(
                                initialValue = 0.4f,
                                targetValue = 1f,
                                animationSpec = infiniteRepeatable(
                                    animation = tween(800),
                                    repeatMode = RepeatMode.Reverse
                                ),
                                label = "pulseAlpha"
                            )
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(statusColor.copy(alpha = if (serviceRunning) alpha else 1f))
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                if (serviceRunning) "LIVE" else "OFF",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = statusColor
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(bottom = 32.dp)
        ) {
            // =================== LIVE DASHBOARD ===================
            item(key = "dashboard") {
                val showDashboard = serviceRunning || totalLinks > 0 || lastCheckTime > 0L
                AnimatedVisibility(visible = showDashboard, enter = fadeIn() + expandVertically()) {
                    Card(
                        shape = RoundedCornerShape(20.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            // Scan progress
                            if (scanInProgress && totalLinks > 0) {
                                val processed = aliveLinks + deadLinks
                                val progress by animateFloatAsState(
                                    targetValue = processed.toFloat() / totalLinks.toFloat(),
                                    animationSpec = tween(500),
                                    label = "progress"
                                )

                                // Big progress ring
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Box(contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(
                                            progress = { progress },
                                            modifier = Modifier.size(56.dp),
                                            strokeWidth = 5.dp,
                                            trackColor = MaterialTheme.colorScheme.outline,
                                            color = CyanAccent
                                        )
                                        Text(
                                            "${(progress * 100).toInt()}%",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = CyanAccent
                                        )
                                    }
                                    Spacer(Modifier.width(16.dp))
                                    Column {
                                        Text(
                                            "Scanning...",
                                            fontWeight = FontWeight.Bold,
                                            color = CyanAccent,
                                            fontSize = 16.sp
                                        )
                                        Text(
                                            "$processed / $totalLinks links checked",
                                            color = OnSurfaceVariantDark,
                                            fontSize = 13.sp
                                        )
                                        Row {
                                            Text(
                                                "⚡ ${String.format("%.1f", scanSpeed)}/sec",
                                                color = AmberWarning,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (scanEta.isNotEmpty()) {
                                                Text(
                                                    "  •  ⏱ $scanEta",
                                                    color = OnSurfaceVariantDark,
                                                    fontSize = 12.sp
                                                )
                                            }
                                        }
                                    }
                                }

                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 16.dp)
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = CyanAccent,
                                    trackColor = MaterialTheme.colorScheme.outline,
                                )
                                Spacer(Modifier.height(16.dp))
                            }

                            // Stats grid
                            Row(modifier = Modifier.fillMaxWidth()) {
                                StatChip(
                                    label = "Total",
                                    value = "$totalLinks",
                                    icon = Icons.AutoMirrored.Outlined.List,
                                    color = CyanAccent,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                StatChip(
                                    label = "Alive",
                                    value = "$aliveLinks",
                                    icon = Icons.Filled.CheckCircle,
                                    color = GreenAlive,
                                    modifier = Modifier.weight(1f)
                                )
                                Spacer(Modifier.width(8.dp))
                                StatChip(
                                    label = "Dead",
                                    value = "$deadLinks",
                                    icon = Icons.Filled.Close,
                                    color = RedDead,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Meta info
                            Spacer(Modifier.height(12.dp))
                            val dateStr = if (lastCheckTime > 0)
                                SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault()).format(Date(lastCheckTime))
                            else "Never"

                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("Last scan: $dateStr", color = OnSurfaceVariantDark, fontSize = 12.sp)
                                if (lastScanDuration > 0 && !scanInProgress) {
                                    val durationSec = lastScanDuration / 1000
                                    val durationStr = when {
                                        durationSec < 60 -> "${durationSec}s"
                                        durationSec < 3600 -> "${durationSec / 60}m ${durationSec % 60}s"
                                        else -> "${durationSec / 3600}h ${(durationSec % 3600) / 60}m"
                                    }
                                    Text("Duration: $durationStr", color = OnSurfaceVariantDark, fontSize = 12.sp)
                                }
                            }
                            if (playlistFileSize > 0 && !scanInProgress) {
                                val sizeStr = when {
                                    playlistFileSize < 1024 -> "${playlistFileSize} B"
                                    playlistFileSize < 1024 * 1024 -> "${playlistFileSize / 1024} KB"
                                    else -> "${String.format("%.1f", playlistFileSize / (1024.0 * 1024.0))} MB"
                                }
                                Text("Playlist: $sizeStr  •  ${String.format("%.1f", scanSpeed)}/sec avg", color = OnSurfaceVariantDark, fontSize = 12.sp)
                            }
                        }
                    }
                }
            }

            // =================== SOURCE TYPE ===================
            item(key = "source_type") {
                Text(
                    "SOURCE",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanAccent,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("M3U" to "M3U/M3U8", "XTREAM" to "Xtream", "MAC" to "Stalker").forEachIndexed { index, (key, label) ->
                        SegmentedButton(
                            selected = sourceType == key,
                            onClick = { sourceType = key },
                            shape = SegmentedButtonDefaults.itemShape(index, 3),
                            colors = SegmentedButtonDefaults.colors(
                                activeContainerColor = CyanAccent.copy(alpha = 0.15f),
                                activeContentColor = CyanAccent,
                            )
                        ) {
                            Text(label, fontSize = 13.sp)
                        }
                    }
                }
            }

            // =================== SOURCE CONFIG ===================
            item(key = "source_config") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        AnimatedContent(targetState = sourceType, label = "sourceAnim") { type ->
                            Column {
                                when (type) {
                                    "M3U" -> {
                                        OutlinedTextField(
                                            value = url,
                                            onValueChange = { url = it },
                                            label = { Text("Playlist URLs (one per line or comma)") },
                                            minLines = 2,
                                            maxLines = 4,
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    }
                                    "XTREAM" -> {
                                        OutlinedTextField(
                                            value = xtreamServer,
                                            onValueChange = { xtreamServer = it },
                                            label = { Text("Server URL") },
                                            placeholder = { Text("http://example.com:8080") },
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        Row(Modifier.fillMaxWidth()) {
                                            OutlinedTextField(
                                                value = xtreamUser,
                                                onValueChange = { xtreamUser = it },
                                                label = { Text("Username") },
                                                modifier = Modifier.weight(1f).padding(end = 4.dp),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                            OutlinedTextField(
                                                value = xtreamPass,
                                                onValueChange = { xtreamPass = it },
                                                label = { Text("Password") },
                                                visualTransformation = PasswordVisualTransformation(),
                                                modifier = Modifier.weight(1f).padding(start = 4.dp),
                                                shape = RoundedCornerShape(12.dp)
                                            )
                                        }
                                    }
                                    "MAC" -> {
                                        OutlinedTextField(
                                            value = macServer,
                                            onValueChange = { macServer = it },
                                            label = { Text("Portal URL") },
                                            placeholder = { Text("http://example.com:8080") },
                                            modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                        OutlinedTextField(
                                            value = macAddress,
                                            onValueChange = { macAddress = it },
                                            label = { Text("MAC Address") },
                                            placeholder = { Text("00:1A:79:...") },
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(12.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // =================== ADVANCED SETTINGS (Collapsible) ===================
            item(key = "settings_header") {
                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { settingsExpanded = !settingsExpanded }
                ) {
                    Column {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Outlined.Settings,
                                contentDescription = null,
                                tint = CyanAccent,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                "Advanced Settings",
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Icon(
                                if (settingsExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                contentDescription = null,
                                tint = OnSurfaceVariantDark
                            )
                        }

                        AnimatedVisibility(visible = settingsExpanded) {
                            Column(modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                                Row(Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = intervalValue,
                                        onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) intervalValue = it },
                                        label = { Text("Interval") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Unit", fontSize = 12.sp, color = OnSurfaceVariantDark)
                                        Spacer(Modifier.height(4.dp))
                                        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                                            listOf("MINUTES" to "Min", "HOURS" to "Hr").forEachIndexed { i, (unit, label) ->
                                                SegmentedButton(
                                                    selected = intervalUnit == unit,
                                                    onClick = { intervalUnit = unit },
                                                    shape = SegmentedButtonDefaults.itemShape(i, 2)
                                                ) {
                                                    Text(label, fontSize = 12.sp)
                                                }
                                            }
                                        }
                                    }
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(Modifier.fillMaxWidth()) {
                                    OutlinedTextField(
                                        value = timeoutSeconds,
                                        onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) timeoutSeconds = it },
                                        label = { Text("Timeout (sec)") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = concurrentLinks,
                                        onValueChange = { if (it.isEmpty() || it.all { c -> c.isDigit() }) concurrentLinks = it },
                                        label = { Text("Parallel scans") },
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(12.dp)
                                    )
                                }

                                Spacer(Modifier.height(12.dp))

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text("Smart Retry", fontWeight = FontWeight.Medium)
                                        Text(
                                            "Retry failed links with GET if HEAD fails",
                                            fontSize = 12.sp,
                                            color = OnSurfaceVariantDark
                                        )
                                    }
                                    Switch(
                                        checked = retryFailed,
                                        onCheckedChange = { retryFailed = it },
                                        colors = SwitchDefaults.colors(checkedTrackColor = CyanAccent)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // =================== ACTION BUTTONS ===================
            item(key = "actions") {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Main action button
                    Button(
                        onClick = {
                            saveSettings()
                            startService(Intent(context, LinkCheckerService::class.java))
                            serviceRunning = true
                        },
                        modifier = Modifier.weight(1f).height(52.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = CyanAccent,
                            contentColor = Color.Black
                        )
                    ) {
                        Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start", fontWeight = FontWeight.Bold)
                    }

                    // Scan now button
                    if (serviceRunning) {
                        OutlinedButton(
                            onClick = {
                                saveSettings()
                                val intent = Intent(context, LinkCheckerService::class.java).apply {
                                    action = LinkCheckerService.ACTION_FORCE_REFRESH
                                }
                                startService(intent)
                            },
                            modifier = Modifier.weight(1f).height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            border = ButtonDefaults.outlinedButtonBorder(true)
                        ) {
                            Icon(Icons.Filled.Refresh, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Scan Now")
                        }
                    }

                    // Stop button
                    if (serviceRunning) {
                        FilledTonalButton(
                            onClick = {
                                val intent = Intent(context, LinkCheckerService::class.java).apply {
                                    action = LinkCheckerService.ACTION_STOP_SERVICE
                                }
                                startService(intent)
                                serviceRunning = false
                            },
                            modifier = Modifier.height(52.dp),
                            shape = RoundedCornerShape(14.dp),
                            colors = ButtonDefaults.filledTonalButtonColors(
                                containerColor = RedDead.copy(alpha = 0.15f),
                                contentColor = RedDead
                            )
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = null, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }

            // =================== NETWORK ACCESS (Collapsible) ===================
            item(key = "network") {
                val showNetwork = serviceRunning || lastCheckTime > 0L
                AnimatedVisibility(visible = showNetwork, enter = fadeIn() + expandVertically()) {
                    Card(
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SurfaceDarkElevated),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { networkExpanded = !networkExpanded }
                    ) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    Icons.Outlined.Share,
                                    contentDescription = null,
                                    tint = CyanAccent,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Playlist URL", fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "Tap to copy • Share with IPTV apps",
                                        fontSize = 12.sp,
                                        color = OnSurfaceVariantDark
                                    )
                                }
                                Icon(
                                    if (networkExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                                    contentDescription = null,
                                    tint = OnSurfaceVariantDark
                                )
                            }

                            // Always show localhost as a quick-copy
                            UrlRow(
                                label = "This device",
                                url = "http://localhost:8080/playlist.m3u",
                                context = context
                            )

                            AnimatedVisibility(visible = networkExpanded) {
                                Column(modifier = Modifier.padding(bottom = 12.dp)) {
                                    for (ip in deviceIps) {
                                        UrlRow(
                                            label = "LAN ($ip)",
                                            url = "http://$ip:8080/playlist.m3u",
                                            context = context
                                        )
                                    }
                                    if (deviceIps.isEmpty()) {
                                        Text(
                                            "   No LAN IP detected. Connect to Wi-Fi.",
                                            fontSize = 12.sp,
                                            color = OnSurfaceVariantDark,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// =================== COMPOSABLE COMPONENTS ===================

@Composable
private fun StatChip(
    label: String,
    value: String,
    icon: ImageVector,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = color.copy(alpha = 0.1f),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(4.dp))
            Text(
                value,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                color = color
            )
            Text(label, fontSize = 11.sp, color = OnSurfaceVariantDark)
        }
    }
}

@Composable
private fun UrlRow(label: String, url: String, context: Context) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("playlist_url", url))
                Toast.makeText(context, "Copied to clipboard!", Toast.LENGTH_SHORT).show()
            }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Outlined.ContentCopy,
            contentDescription = "Copy",
            tint = CyanAccent,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(label, fontSize = 11.sp, color = OnSurfaceVariantDark)
            Text(
                url,
                fontSize = 13.sp,
                color = CyanAccent,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

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
    } catch (_: Exception) {}
    return ips
}
