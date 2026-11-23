package com.mediaremote.receiver.ui

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.mediaremote.receiver.protocol.ProtocolConstants
import com.mediaremote.receiver.security.PairingManager
import java.net.URLEncoder

@Composable
fun QrPairingSheet(
    ip: String,
    pairingManager: PairingManager,
    onDismiss: () -> Unit
) {
    val activeToken = remember { pairingManager.issueOneTimePairingToken() }
    val pairingUrl = remember(ip, activeToken) {
        val encodedToken = URLEncoder.encode(activeToken.token, "UTF-8")
        "http://$ip:${ProtocolConstants.DEFAULT_PORT}/?pair=$encodedToken"
    }

    val qrBitmap = remember(pairingUrl) { generateQrBitmap(pairingUrl, 640) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Scan QR or Open in Any Browser") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "Pairing QR Code",
                    modifier = Modifier.size(240.dp)
                )
                Text(
                    "Open this address in any browser on the same Wi-Fi (phone, tablet, or computer). It swaps this one-time token (120s TTL) for a stored secret.",
                    style = MaterialTheme.typography.bodySmall
                )
                Text(
                    pairingUrl,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Done") }
        }
    )
}

private fun generateQrBitmap(content: String, size: Int): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
    for (x in 0 until size) {
        for (y in 0 until size) {
            bmp.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
    }
    return bmp
}
