package com.mediaremote.receiver.media

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.service.notification.NotificationListenerService

class MediaNotificationListenerService : NotificationListenerService() {

    override fun onListenerConnected() {
        super.onListenerConnected()
        listenerConnected = true
        onPermissionStateChanged?.invoke(true)
    }

    override fun onListenerDisconnected() {
        listenerConnected = false
        onPermissionStateChanged?.invoke(false)
        super.onListenerDisconnected()
    }

    companion object {
        @Volatile
        var listenerConnected: Boolean = false
            private set

        var onPermissionStateChanged: ((Boolean) -> Unit)? = null

        fun componentName(context: Context): ComponentName =
            ComponentName(context, MediaNotificationListenerService::class.java)

        fun isPermissionGranted(context: Context): Boolean {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                "enabled_notification_listeners"
            ) ?: return false
            val target = componentName(context).flattenToString()
            return enabled.split(":").any { it.equals(target, ignoreCase = true) }
        }
    }
}
