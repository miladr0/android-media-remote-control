package com.mediaremote.receiver.security

import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.mediaremote.receiver.protocol.ProtocolConstants
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.UUID

@Serializable
data class PairedClientRecord(
    val clientId: String,
    val clientName: String,
    val sharedSecretB64: String,
    val keyFingerprint: String,
    val pairedAt: Long
)

data class ActivePairingSession(
    val token: String,
    val expiresAt: Long,
    var consumed: Boolean = false
)

class PairingManager(context: Context) {
    private val json = Json { ignoreUnknownKeys = true }
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "mediaremote_secure_store",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    val deviceId: String = prefs.getString("device_id", null) ?: run {
        val created = "and-${UUID.randomUUID().toString().take(12)}"
        prefs.edit().putString("device_id", created).apply()
        created
    }

    val deviceName: String = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

    private val deviceKeyPair: KeyPair = loadOrCreateIdentityKeyPair()

    private val _pairedClients = MutableStateFlow(loadPairedClients())
    val pairedClients: StateFlow<List<PairedClientRecord>> = _pairedClients.asStateFlow()

    @Volatile
    private var currentPairing: ActivePairingSession? = null

    val devicePublicKeyB64: String
        get() = CryptoEngine.encodePublicKey(deviceKeyPair)

    private fun loadOrCreateIdentityKeyPair(): KeyPair {
        val privB64 = prefs.getString("identity_priv", null)
        val pubB64 = prefs.getString("identity_pub", null)
        if (privB64 != null && pubB64 != null) {
            val kf = KeyFactory.getInstance("EC")
            val priv: PrivateKey = kf.generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(privB64)))
            val pub: PublicKey = kf.generatePublic(X509EncodedKeySpec(Base64.getDecoder().decode(pubB64)))
            return KeyPair(pub, priv)
        }
        val kp = CryptoEngine.generateEcdhKeyPair()
        prefs.edit()
            .putString("identity_priv", CryptoEngine.encodePrivateKey(kp))
            .putString("identity_pub", CryptoEngine.encodePublicKey(kp))
            .apply()
        return kp
    }

    @Synchronized
    fun issueOneTimePairingToken(): ActivePairingSession {
        val token = CryptoEngine.randomTokenBase64(24)
        val session = ActivePairingSession(
            token = token,
            expiresAt = System.currentTimeMillis() + ProtocolConstants.PAIRING_TOKEN_TTL_MS
        )
        currentPairing = session
        return session
    }

    @Synchronized
    fun completePairing(
        clientId: String,
        clientName: String,
        token: String
    ): Result<PairedClientRecord> {
        val active = currentPairing
            ?: return Result.failure(SecurityException("PAIRING_EXPIRED"))
        if (active.consumed || System.currentTimeMillis() > active.expiresAt) {
            return Result.failure(SecurityException("PAIRING_EXPIRED"))
        }
        val expectedBytes = active.token.toByteArray()
        val providedBytes = token.toByteArray()
        if (!CryptoEngine.constantTimeEquals(expectedBytes, providedBytes)) {
            return Result.failure(SecurityException("INVALID_TOKEN"))
        }
        active.consumed = true

        val deviceSecretB64 = CryptoEngine.randomTokenBase64(32)
        val record = PairedClientRecord(
            clientId = clientId,
            clientName = clientName,
            sharedSecretB64 = deviceSecretB64,
            keyFingerprint = CryptoEngine.keyFingerprint(deviceSecretB64),
            pairedAt = System.currentTimeMillis()
        )
        val updated = _pairedClients.value.filterNot { it.clientId == clientId } + record
        savePairedClients(updated)
        return Result.success(record)
    }

    fun getPairedClient(clientId: String): PairedClientRecord? =
        _pairedClients.value.firstOrNull { it.clientId == clientId }

    @Synchronized
    fun revokeClient(clientId: String) {
        val updated = _pairedClients.value.filterNot { it.clientId == clientId }
        savePairedClients(updated)
    }

    private fun loadPairedClients(): List<PairedClientRecord> {
        val raw = prefs.getString("paired_clients_json", null) ?: return emptyList()
        return runCatching { json.decodeFromString<List<PairedClientRecord>>(raw) }
            .getOrDefault(emptyList())
    }

    private fun savePairedClients(list: List<PairedClientRecord>) {
        prefs.edit().putString("paired_clients_json", json.encodeToString(list)).apply()
        _pairedClients.value = list
    }
}
