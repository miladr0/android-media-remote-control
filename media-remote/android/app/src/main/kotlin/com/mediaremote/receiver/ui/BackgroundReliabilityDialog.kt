package com.mediaremote.receiver.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.mediaremote.receiver.media.MediaNotificationListenerService

private data class OemBackgroundGuide(
    val vendorLabel: String,
    val buttonLabel: String,
    val instructions: String,
    val intents: List<Intent>
)

@Composable
fun BackgroundReliabilityDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val listenerEnabled = MediaNotificationListenerService.isPermissionGranted(context)
    val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
    val batteryIgnored = pm.isIgnoringBatteryOptimizations(context.packageName)
    val oemGuide = remember(context) { detectOemBackgroundGuide(context) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Background Reliability") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    "Configure these permissions so Android keeps media detection and the local Wi-Fi server active while the screen is off.",
                    style = MaterialTheme.typography.bodySmall
                )

                Text(
                    "1. Notification Access (${if (listenerEnabled) "Granted" else "Required"})",
                    style = MaterialTheme.typography.titleSmall
                )
                Button(onClick = {
                    launchWithAppDetailsFallback(
                        context,
                        listOf(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                    )
                }) {
                    Text("Open Notification Access")
                }

                Text(
                    "2. Battery Optimization (${if (batteryIgnored) "Unrestricted" else "Set to Unrestricted"})",
                    style = MaterialTheme.typography.titleSmall
                )
                Button(onClick = {
                    launchWithAppDetailsFallback(
                        context,
                        listOf(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                                data = Uri.parse("package:${context.packageName}")
                            },
                            Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                        )
                    )
                }) {
                    Text("Allow Unrestricted Battery")
                }

                if (oemGuide != null) {
                    Text(
                        "3. ${oemGuide.vendorLabel} Background / Autostart",
                        style = MaterialTheme.typography.titleSmall
                    )
                    Text(oemGuide.instructions, style = MaterialTheme.typography.bodySmall)
                    OutlinedButton(onClick = {
                        launchWithAppDetailsFallback(context, oemGuide.intents)
                    }) {
                        Text(oemGuide.buttonLabel)
                    }
                } else {
                    Text("3. App Background Settings", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "If your device pauses background apps, open App Info -> Battery and choose Unrestricted.",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedButton(onClick = {
                        launchWithAppDetailsFallback(context, emptyList())
                    }) {
                        Text("Open App Info")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

private fun detectOemBackgroundGuide(context: Context): OemBackgroundGuide? {
    val mfg = Build.MANUFACTURER.orEmpty().lowercase()
    val brand = Build.BRAND.orEmpty().lowercase()
    val id = "$mfg $brand"
    val pkg = context.packageName

    return when {
        id.contains("xiaomi") || id.contains("redmi") || id.contains("poco") -> OemBackgroundGuide(
            vendorLabel = "Xiaomi / Redmi / POCO (MIUI & HyperOS)",
            buttonLabel = "Open MIUI / HyperOS Autostart",
            instructions = "Enable Autostart and set Battery saver to 'No restrictions' so MIUI/HyperOS does not close the background socket.",
            intents = listOf(
                Intent().setComponent(
                    ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                ),
                Intent("miui.intent.action.APP_PERM_EDITOR").apply {
                    putExtra("extra_pkgname", pkg)
                }
            )
        )
        id.contains("samsung") -> OemBackgroundGuide(
            vendorLabel = "Samsung (One UI)",
            buttonLabel = "Open One UI Battery / Sleeping Apps",
            instructions = "Add LocalCast Receiver to 'Never auto sleeping apps' under Device Care -> Battery -> Background usage limits.",
            intents = listOf(
                Intent().setComponent(
                    ComponentName("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                ),
                Intent().setComponent(
                    ComponentName("com.samsung.android.sm", "com.samsung.android.sm.battery.ui.BatteryActivity")
                )
            )
        )
        id.contains("huawei") || id.contains("honor") -> OemBackgroundGuide(
            vendorLabel = "Huawei / Honor",
            buttonLabel = "Open App Launch Settings",
            instructions = "Set App Launch to 'Manage manually' and allow Auto-launch, Secondary launch, and Run in background.",
            intents = listOf(
                Intent().setComponent(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                ),
                Intent().setComponent(
                    ComponentName("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                ),
                Intent().setComponent(
                    ComponentName("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                )
            )
        )
        id.contains("oppo") || id.contains("realme") || id.contains("oneplus") -> OemBackgroundGuide(
            vendorLabel = "Oppo / Realme / OnePlus (ColorOS & OxygenOS)",
            buttonLabel = "Open Background / Auto-launch",
            instructions = "Allow background activity and auto-launch in Battery / App Management.",
            intents = listOf(
                Intent().setComponent(
                    ComponentName("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                ),
                Intent().setComponent(
                    ComponentName("com.oplus.battery", "com.oplus.powermanager.fuelgaue.PowerConsumptionActivity")
                ),
                Intent().setComponent(
                    ComponentName("com.oneplus.security", "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity")
                )
            )
        )
        id.contains("vivo") || id.contains("iqoo") -> OemBackgroundGuide(
            vendorLabel = "Vivo / iQOO",
            buttonLabel = "Open Background Power Settings",
            instructions = "Enable 'Allow high background power consumption' / Autostart for LocalCast Receiver.",
            intents = listOf(
                Intent().setComponent(
                    ComponentName("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManagerActivity")
                ),
                Intent().setComponent(
                    ComponentName("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                )
            )
        )
        id.contains("asus") -> OemBackgroundGuide(
            vendorLabel = "Asus",
            buttonLabel = "Open Asus Auto-start Manager",
            instructions = "Allow LocalCast Receiver in Mobile Manager -> Permissions -> Auto-start Manager.",
            intents = listOf(
                Intent().setComponent(
                    ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.powersaver.PowerSaverSettings")
                ),
                Intent().setComponent(
                    ComponentName("com.asus.mobilemanager", "com.asus.mobilemanager.MainActivity")
                )
            )
        )
        else -> null
    }
}

private fun launchWithAppDetailsFallback(context: Context, candidates: List<Intent>) {
    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
    }
    for (intent in candidates + fallback) {
        val launched = runCatching {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.isSuccess
        if (launched) break
    }
}
