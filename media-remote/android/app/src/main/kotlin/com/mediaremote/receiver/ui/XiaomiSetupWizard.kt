package com.mediaremote.receiver.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mediaremote.receiver.media.MediaNotificationListenerService

@Composable
fun XiaomiSetupWizard(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val listenerEnabled = MediaNotificationListenerService.isPermissionGranted(context)
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Xiaomi MIUI / HyperOS Setup") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "1. Notification Access (${if (listenerEnabled) "Granted" else "Required"})",
                    style = MaterialTheme.typography.titleSmall
                )
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }) {
                    Text("Open Notification Access")
                }

                Text(
                    "2. Battery Optimization (${if (batteryIgnored) "Unrestricted" else "Set to No Restrictions"})",
                    style = MaterialTheme.typography.titleSmall
                )
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:${context.packageName}")
                    }
                    context.startActivity(intent)
                }) {
                    Text("Allow Unrestricted Battery")
                }

                Text("3. MIUI Autostart Permission", style = MaterialTheme.typography.titleSmall)
                OutlinedButton(onClick = { openMiuiAutostart(context) }) {
                    Text("Open Xiaomi Autostart")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun openMiuiAutostart(context: Context) {
    val candidates = listOf(
        Intent().setComponent(
            ComponentName(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
            )
        ),
        Intent("miui.intent.action.APP_PERM_EDITOR").apply {
            putExtra("extra_pkgname", context.packageName)
        },
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    )
    for (intent in candidates) {
        val launched = runCatching { context.startActivity(intent) }.isSuccess
        if (launched) break
    }
}
