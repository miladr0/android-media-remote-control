package com.mediaremote.receiver.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import com.mediaremote.receiver.protocol.ProtocolConstants

class NsdAdvertiser(private val context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private var multicastLock: WifiManager.MulticastLock? = null
    private var registrationListener: NsdManager.RegistrationListener? = null
    private var registered = false

    fun register(serviceName: String, port: Int, deviceId: String, keyFingerprint: String) {
        if (registered) return
        multicastLock = wifiManager.createMulticastLock("mediaremote_nsd").apply {
            setReferenceCounted(false)
            acquire()
        }

        val info = NsdServiceInfo().apply {
            this.serviceName = serviceName
            this.serviceType = ProtocolConstants.SERVICE_TYPE
            this.port = port
            setAttribute("v", ProtocolConstants.VERSION.toString())
            setAttribute("id", deviceId)
            setAttribute("fp", keyFingerprint)
        }

        val listener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(nsdServiceInfo: NsdServiceInfo) {
                registered = true
            }
            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                registered = false
            }
            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                registered = false
            }
            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {}
        }
        registrationListener = listener
        runCatching {
            nsdManager.registerService(info, NsdManager.PROTOCOL_DNS_SD, listener)
        }
    }

    fun unregister() {
        registrationListener?.let { listener ->
            runCatching { nsdManager.unregisterService(listener) }
        }
        registrationListener = null
        registered = false
        multicastLock?.let { lock ->
            if (lock.isHeld) lock.release()
        }
        multicastLock = null
    }
}
