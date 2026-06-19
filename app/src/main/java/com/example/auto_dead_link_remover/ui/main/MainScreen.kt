package com.example.auto_dead_link_remover.ui.main

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.compose.foundation.layout.*
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(LinkCheckerService.PREFS_NAME, Context.MODE_PRIVATE)
    
    var url by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_PLAYLIST_URL, "") ?: "") }
    var intervalValue by remember { mutableStateOf(prefs.getLong(LinkCheckerService.KEY_INTERVAL_VALUE, 1L).toString()) }
    var intervalUnit by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_INTERVAL_UNIT, "HOURS") ?: "HOURS") }
    var timeoutSeconds by remember { mutableStateOf(prefs.getLong(LinkCheckerService.KEY_TIMEOUT_SECONDS, 5L).toString()) }
    var serviceStarted by remember { mutableStateOf(false) }

    var lastCheckTime by remember { mutableStateOf(prefs.getLong("last_check_time", 0L)) }
    var totalLinks by remember { mutableStateOf(prefs.getInt("total_links", 0)) }
    var aliveLinks by remember { mutableStateOf(prefs.getInt("alive_links", 0)) }
    var deadLinks by remember { mutableStateOf(prefs.getInt("dead_links", 0)) }

    val listener = remember {
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { sharedPreferences, key ->
            when (key) {
                "last_check_time" -> lastCheckTime = sharedPreferences.getLong(key, 0L)
                "total_links" -> totalLinks = sharedPreferences.getInt(key, 0)
                "alive_links" -> aliveLinks = sharedPreferences.getInt(key, 0)
                "dead_links" -> deadLinks = sharedPreferences.getInt(key, 0)
            }
        }
    }

    DisposableEffect(prefs) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose {
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "Auto Dead Link Remover",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("IPTV Playlist URL (.m3u, .m3u8)") },
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = intervalValue,
                onValueChange = { if (it.isEmpty() || it.all { char -> char.isDigit() }) intervalValue = it },
                label = { Text("Update Interval") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(16.dp))

            Row {
                Button(
                    onClick = { intervalUnit = "MINUTES" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (intervalUnit == "MINUTES") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (intervalUnit == "MINUTES") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("Minutes")
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = { intervalUnit = "HOURS" },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (intervalUnit == "HOURS") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = if (intervalUnit == "HOURS") MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Text("Hours")
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        OutlinedTextField(
            value = timeoutSeconds,
            onValueChange = { if (it.isEmpty() || it.all { char -> char.isDigit() }) timeoutSeconds = it },
            label = { Text("Connection Timeout (Seconds) - e.g. 5") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth()
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Button(
                onClick = {
                    val finalInterval = intervalValue.toLongOrNull() ?: 1L
                    val finalTimeout = timeoutSeconds.toLongOrNull() ?: 5L
                    prefs.edit()
                        .putString(LinkCheckerService.KEY_PLAYLIST_URL, url)
                        .putLong(LinkCheckerService.KEY_INTERVAL_VALUE, finalInterval)
                        .putString(LinkCheckerService.KEY_INTERVAL_UNIT, intervalUnit)
                        .putLong(LinkCheckerService.KEY_TIMEOUT_SECONDS, finalTimeout)
                        .apply()
                    
                    val serviceIntent = Intent(context, LinkCheckerService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(serviceIntent)
                    } else {
                        context.startService(serviceIntent)
                    }
                    serviceStarted = true
                },
                modifier = Modifier.padding(16.dp)
            ) {
                Text("Save & Start Service")
            }

            if (serviceStarted || prefs.getString(LinkCheckerService.KEY_PLAYLIST_URL, "")?.isNotEmpty() == true) {
                Button(
                    onClick = {
                        val serviceIntent = Intent(context, LinkCheckerService::class.java).apply {
                            action = LinkCheckerService.ACTION_FORCE_REFRESH
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(serviceIntent)
                        } else {
                            context.startService(serviceIntent)
                        }
                    },
                    modifier = Modifier.padding(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary)
                ) {
                    Text("Force Check Now")
                }
            }
        }
        
        if (serviceStarted || prefs.getString(LinkCheckerService.KEY_PLAYLIST_URL, "")?.isNotEmpty() == true) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Dashboard", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val dateStr = if (lastCheckTime > 0) SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date(lastCheckTime)) else "Never"
                    
                    Text("Last Checked: $dateStr")
                    Text("Total Links Found: $totalLinks")
                    Text("Alive Links: $aliveLinks", color = androidx.compose.ui.graphics.Color(0xFF4CAF50))
                    Text("Dead Links Removed: $deadLinks", color = MaterialTheme.colorScheme.error)
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Point your IPTV Player (like TiviMate) to:")
                    Text(
                        text = "http://localhost:8080/playlist.m3u",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    }
}
