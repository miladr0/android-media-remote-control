package com.mediaremote.receiver.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mediaremote.receiver.media.MediaNotificationListenerService
import com.mediaremote.receiver.media.MediaSessionCoordinator
import com.mediaremote.receiver.network.MediaWebSocketServer
import com.mediaremote.receiver.protocol.ProtocolConstants
import com.mediaremote.receiver.security.PairingManager
import com.mediaremote.receiver.service.MediaRemoteForegroundService
import java.net.Inet4Address

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReceiverScreen(
    pairingManager: PairingManager,
    coordinator: MediaSessionCoordinator,
    webSocketServer: MediaWebSocketServer
) {
    val context = LocalContext.current
    val state by coordinator.state.collectAsState()
    val pairedClients by pairingManager.pairedClients.collectAsState()
    val connectedClients by webSocketServer.connectedClients.collectAsState()
    val activeConnectionCount by webSocketServer.activeConnectionCount.collectAsState()
    val serviceRunning by MediaRemoteForegroundService.isRunning.collectAsState()
    var showQrSheet by remember { mutableStateOf(false) }
    var showReliabilityDialog by remember { mutableStateOf(false) }
    var localIp by remember { mutableStateOf(resolveLocalIpv4(context)) }
    val listenerGranted = MediaNotificationListenerService.isPermissionGranted(context)

    DisposableEffect(context) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                localIp = resolveLocalIpv4(context)
            }

            override fun onLost(network: Network) {
                localIp = resolveLocalIpv4(context)
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                localIp = resolveLocalIpv4(context)
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                localIp = resolveLocalIpv4(context)
            }
        }
        runCatching { cm.registerDefaultNetworkCallback(callback) }
        onDispose {
            runCatching { cm.unregisterNetworkCallback(callback) }
        }
    }

    LaunchedEffect(listenerGranted) {
        if (listenerGranted) coordinator.startMonitoring()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LocalCast Receiver", fontWeight = FontWeight.SemiBold) },
                actions = {
                    TextButton(onClick = { showReliabilityDialog = true }) {
                        Text("Setup")
                    }
                    Button(
                        onClick = {
                            MediaRemoteForegroundService.startService(context)
                            showQrSheet = true
                        },
                        enabled = localIp != null
                    ) {
                        Text("Pair Device")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!listenerGranted) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("Notification Access Required", style = MaterialTheme.typography.titleMedium)
                            Text("Android requires Notification Access so MediaSessionManager can inspect active media apps.")
                            Button(onClick = { showReliabilityDialog = true }) {
                                Text("Open Reliability Setup")
                            }
                        }
                    }
                }
            }

            item {
                val ip = localIp
                Card(
                    colors = if (ip == null) {
                        CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                    } else {
                        CardDefaults.cardColors()
                    }
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("Web Remote URL", style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = if (serviceRunning) "Server Running" else "Server Stopped",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (serviceRunning) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (ip != null) {
                            Text(
                                text = "http://$ip:${ProtocolConstants.DEFAULT_PORT}/",
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.SemiBold,
                                style = MaterialTheme.typography.titleSmall
                            )
                            Text(
                                text = "WebSocket: ws://$ip:${ProtocolConstants.DEFAULT_PORT}/mediaremote",
                                fontFamily = FontFamily.Monospace,
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Open this address in any browser on the same Wi-Fi (phone, tablet, or computer), or tap 'Pair Device' for a QR code.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        } else {
                            Text(
                                text = "Wi-Fi is off or local IPv4 address is unavailable.",
                                fontWeight = FontWeight.Medium,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Connect this Android device to your local Wi-Fi network to generate http://<wifi-ip>:${ProtocolConstants.DEFAULT_PORT}/ and pair a browser.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                        Text("mDNS Service: ${ProtocolConstants.SERVICE_TYPE}", style = MaterialTheme.typography.bodySmall)
                        Text(
                            "Connected browsers: ${if (connectedClients.isEmpty()) "None" else connectedClients.joinToString()} ($activeConnectionCount socket(s))",
                            style = MaterialTheme.typography.bodySmall
                        )
                        if (!serviceRunning) {
                            OutlinedButton(onClick = { MediaRemoteForegroundService.startService(context) }) {
                                Text("Start Local Server")
                            }
                        }
                    }
                }
            }

            item {
                Card {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        val playerDisplay = state.appName ?: state.packageName ?: "No active media session"
                        val stateDisplay = if (state.isLastPlayed) "Last played" else state.playbackState
                        Text("Media Session", style = MaterialTheme.typography.titleMedium)
                        Text("App: $playerDisplay")
                        Text("Track: ${state.title ?: "—"}")
                        Text("Artist: ${state.artist ?: "—"}")
                        Text("State: $stateDisplay")
                        Text(
                            "Supported Actions: ${if (state.supportedActions.isEmpty()) "None" else state.supportedActions.joinToString()}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }

            if (state.sessions.size > 1) {
                item {
                    Text("Detected Media Apps (${state.sessions.size})", style = MaterialTheme.typography.titleMedium)
                }
                items(state.sessions, key = { it.sessionId }) { summary ->
                    Card {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(summary.appName ?: summary.packageName, fontWeight = FontWeight.Medium)
                                Text(summary.title ?: summary.playbackState, style = MaterialTheme.typography.bodySmall)
                            }
                            if (summary.isSelected) {
                                Text("Selected", color = MaterialTheme.colorScheme.primary)
                            } else {
                                OutlinedButton(onClick = { coordinator.selectSession(summary.sessionId) }) {
                                    Text("Select")
                                }
                            }
                        }
                    }
                }
            }

            item {
                Text("Paired Devices (${pairedClients.size})", style = MaterialTheme.typography.titleMedium)
            }
            if (pairedClients.isEmpty()) {
                item {
                    Text("No paired devices yet. Tap 'Pair Device' to show a one-time QR code.", style = MaterialTheme.typography.bodyMedium)
                }
            } else {
                items(pairedClients, key = { it.clientId }) { client ->
                    Card {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text(client.clientName, fontWeight = FontWeight.Medium)
                                Text("Key FP: ${client.keyFingerprint}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                            }
                            OutlinedButton(
                                onClick = {
                                    pairingManager.revokeClient(client.clientId)
                                    webSocketServer.disconnectClient(client.clientId) {
                                        if (pairingManager.pairedClients.value.isEmpty() &&
                                            webSocketServer.activeConnectionCount.value == 0
                                        ) {
                                            MediaRemoteForegroundService.stopService(context)
                                        }
                                    }
                                }
                            ) {
                                Text("Revoke")
                            }
                        }
                    }
                }
            }
        }
    }

    val activeIp = localIp
    if (showQrSheet && activeIp != null) {
        QrPairingSheet(
            ip = activeIp,
            pairingManager = pairingManager,
            onDismiss = { showQrSheet = false }
        )
    }
    if (showReliabilityDialog) {
        BackgroundReliabilityDialog(onDismiss = { showReliabilityDialog = false })
    }
}

fun resolveLocalIpv4(context: Context): String? {
    val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    val networks = buildList<Network> {
        cm.activeNetwork?.let { add(it) }
        cm.allNetworks.forEach { if (it !in this) add(it) }
    }

    for (network in networks) {
        val caps = cm.getNetworkCapabilities(network) ?: continue
        val isLan = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        if (!isLan) continue

        val lp = cm.getLinkProperties(network) ?: continue
        val ipv4 = lp.linkAddresses
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?: lp.linkAddresses
                .map { it.address }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress }
        if (ipv4 != null && !ipv4.hostAddress.isNullOrBlank()) {
            return ipv4.hostAddress
        }
    }
    return null
}
