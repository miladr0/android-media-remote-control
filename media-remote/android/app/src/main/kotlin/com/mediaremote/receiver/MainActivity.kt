package com.mediaremote.receiver

import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import com.mediaremote.receiver.service.MediaRemoteForegroundService
import com.mediaremote.receiver.ui.ReceiverScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val serviceIntent = Intent(this, MediaRemoteForegroundService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent)
        } else {
            startService(serviceIntent)
        }

        val app = application as MediaRemoteApp
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                ReceiverScreen(
                    pairingManager = app.pairingManager,
                    coordinator = app.coordinator,
                    webSocketServer = app.webSocketServer
                )
            }
        }
    }
}
