package com.mediaremote.receiver.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import com.mediaremote.receiver.MainActivity
import com.mediaremote.receiver.MediaRemoteApp
import com.mediaremote.receiver.protocol.ProtocolConstants
import com.mediaremote.receiver.security.CryptoEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class MediaRemoteForegroundService : Service() {
    private var wifiLock: WifiManager.WifiLock? = null
    private var serverStarted = false

    override fun onCreate() {
        super.onCreate()
        createChannel()
        startForeground(
            NOTIFICATION_ID,
            buildNotification("Web remote active on port ${ProtocolConstants.DEFAULT_PORT}")
        )
        _isRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_EXIT || intent?.action == ACTION_STOP) {
            if (intent.action == ACTION_EXIT) {
                setExplicitExit(this, true)
            }
            shutdownServerAndLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        setExplicitExit(this, false)
        ensureServerStarted()
        _isRunning.value = true
        return START_STICKY
    }

    override fun onDestroy() {
        shutdownServerAndLocks()
        _isRunning.value = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureServerStarted() {
        if (serverStarted) return
        serverStarted = true
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

    private fun shutdownServerAndLocks() {
        if (serverStarted) {
            serverStarted = false
            val app = application as MediaRemoteApp
            runCatching { app.nsdAdvertiser.unregister() }
            runCatching { app.webSocketServer.stop() }
            runCatching { app.coordinator.stopMonitoring() }
        }
        wifiLock?.let { lock ->
            runCatching { if (lock.isHeld) lock.release() }
        }
        wifiLock = null
        _isRunning.value = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "LocalCast Media Remote Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Keeps the local Wi-Fi web remote and WebSocket server running"
            }
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exitIntent = Intent(this, MediaRemoteForegroundService::class.java).apply {
            action = ACTION_EXIT
        }
        val exitPendingIntent = PendingIntent.getService(
            this,
            1,
            exitIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val exitAction = Notification.Action.Builder(
            Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
            "Exit",
            exitPendingIntent
        ).build()

        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("LocalCast Receiver Active")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(openIntent)
            .addAction(exitAction)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "mediaremote_fg"
        private const val NOTIFICATION_ID = 1001
        private const val PREFS_NAME = "mediaremote_service_prefs"
        private const val KEY_EXPLICIT_EXIT = "explicit_exit"
        const val ACTION_EXIT = "com.mediaremote.receiver.ACTION_EXIT"
        const val ACTION_STOP = "com.mediaremote.receiver.STOP"

        private val _isRunning = MutableStateFlow(false)
        val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

        fun startService(context: Context) {
            setExplicitExit(context, false)
            val intent = Intent(context, MediaRemoteForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopService(context: Context) {
            val intent = Intent(context, MediaRemoteForegroundService::class.java)
            context.stopService(intent)
        }

        fun wasExplicitlyExited(context: Context): Boolean =
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(KEY_EXPLICIT_EXIT, false)

        private fun setExplicitExit(context: Context, exited: Boolean) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_EXPLICIT_EXIT, exited)
                .commit()
        }
    }
}
