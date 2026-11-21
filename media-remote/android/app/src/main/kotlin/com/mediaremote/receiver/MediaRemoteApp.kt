package com.mediaremote.receiver

import android.app.Application
import com.mediaremote.receiver.media.MediaSessionCoordinator
import com.mediaremote.receiver.network.MediaWebSocketServer
import com.mediaremote.receiver.network.NsdAdvertiser
import com.mediaremote.receiver.protocol.ProtocolConstants
import com.mediaremote.receiver.security.PairingManager

class MediaRemoteApp : Application() {
    lateinit var pairingManager: PairingManager
        private set
    lateinit var coordinator: MediaSessionCoordinator
        private set
    lateinit var nsdAdvertiser: NsdAdvertiser
        private set
    lateinit var webSocketServer: MediaWebSocketServer
        private set

    override fun onCreate() {
        super.onCreate()
        pairingManager = PairingManager(this)
        coordinator = MediaSessionCoordinator(this, pairingManager.deviceId, pairingManager.deviceName)
        nsdAdvertiser = NsdAdvertiser(this)
        webSocketServer = MediaWebSocketServer(this, ProtocolConstants.DEFAULT_PORT, pairingManager, coordinator)
    }
}
