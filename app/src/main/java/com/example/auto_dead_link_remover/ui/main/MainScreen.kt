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

@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences(LinkCheckerService.PREFS_NAME, Context.MODE_PRIVATE)
    
    var url by remember { mutableStateOf(prefs.getString(LinkCheckerService.KEY_PLAYLIST_URL, "") ?: "") }
    var serviceStarted by remember { mutableStateOf(false) }

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
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = {
                prefs.edit().putString(LinkCheckerService.KEY_PLAYLIST_URL, url).apply()
                
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
            Spacer(modifier = Modifier.height(32.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Service is configured!", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
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
