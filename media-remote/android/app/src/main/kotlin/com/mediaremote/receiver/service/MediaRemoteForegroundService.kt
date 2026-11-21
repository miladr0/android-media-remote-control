package com.mediaremote.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.mediaremote.receiver.MainActivity
import com.mediaremote.receiver.MediaRemoteApp
import com.mediaremote.receiver.protocol.ProtocolConstants
import com.mediaremote.receiver.security.CryptoEngine

class MediaRemoteForegroundService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(NOTIFICATION_ID, buildNotification("Listening on port ${ProtocolConstants.DEFAULT_PORT}"))

        val app = application as MediaRemoteApp
        val wifiMgr = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wifiMgr.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "MediaRemote:WifiLock").apply {
            setReferenceCounted(false)
            acquire()
        }

        app.coordinator.startMonitoring()
        app.webSocketServer.start()
        app.nsdAdvertiser.register(
            serviceName = app.pairingManager.deviceName.take(32),
            port = ProtocolConstants.DEFAULT_PORT,
            deviceId = app.pairingManager.deviceId,
            keyFingerprint = CryptoEngine.keyFingerprint(app.pairingManager.devicePublicKeyB64)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        val app = application as MediaRemoteApp
        app.nsdAdvertiser.unregister()
        app.webSocketServer.stop()
        app.coordinator.stopMonitoring()
        wifiLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Local Media Remote Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps local Wi-Fi WebSocket server available for paired iPhones"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalCast Receiver Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mediaremote_fg"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_STOP = "com.mediaremote.receiver.STOP"
    }
}
