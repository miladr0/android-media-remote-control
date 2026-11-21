package com.mediaremote.receiver.protocol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class ProtocolEnvelope(
    val v: Int = 1,
    val type: String,
    val id: String,
    val seq: Long,
    val ts: Long,
    val nonce: String? = null,
    val data: JsonObject
)

@Serializable
data class HelloPayload(
    val clientId: String,
    val clientName: String,
    val supportedVersions: List<Int>
)

@Serializable
data class HelloAckPayload(
    val selectedVersion: Int,
    val deviceId: String,
    val deviceName: String,
    val requiresPairing: Boolean,
    val serverTime: Long
)

@Serializable
data class PairRequestPayload(
    val clientId: String,
    val clientName: String,
    val pairingToken: String
)

@Serializable
data class PairAckPayload(
    val deviceId: String,
    val deviceName: String,
    val deviceSecret: String,
    val keyFingerprint: String
)

@Serializable
data class AuthChallengePayload(
    val serverNonce: String,
    val sessionSalt: String
)

@Serializable
data class AuthResponsePayload(
    val clientId: String,
    val clientNonce: String,
    val proofHmac: String
)

@Serializable
data class AuthOkPayload(
    val clientId: String,
    val serverProofHmac: String,
    val encryptionEnabled: Boolean
)

@Serializable
data class CommandPayload(
    val action: String,
    val targetSessionId: String? = null,
    val volume: Int? = null
)

@Serializable
data class AckPayload(
    val requestId: String,
    val action: String,
    val status: String,
    val reason: String? = null
)

@Serializable
data class VolumeState(
    val current: Int,
    val max: Int,
    val percent: Int,
    val isFixed: Boolean
)

@Serializable
data class SessionSummary(
    val sessionId: String,
    val packageName: String,
    val appName: String,
    val title: String? = null,
    val playbackState: String,
    val isSelected: Boolean
)

@Serializable
data class MediaStatePayload(
    val deviceId: String,
    val deviceName: String,
    val sessionId: String? = null,
    val packageName: String? = null,
    val appName: String? = null,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val playbackState: String,
    val supportedActions: List<String>,
    val artworkBase64: String? = null,
    val artworkHash: String? = null,
    val volume: VolumeState,
    val sessions: List<SessionSummary>
)

@Serializable
data class ErrorPayload(
    val requestId: String? = null,
    val code: String,
    val message: String,
    val recoverable: Boolean
)

@Serializable
data class PingPayload(val sentAt: Long)

@Serializable
data class SyncRequestPayload(val reason: String)

@Serializable
data class PongPayload(val echoSentAt: Long, val serverTime: Long)

@Serializable
data class EncryptedPayload(
    val iv: String,
    val ciphertext: String,
    val tag: String
)

@Serializable
data class PairingQrPayload(
    val url: String,
    val deviceId: String,
    val deviceName: String,
    val ip: String,
    val port: Int,
    val pairingToken: String,
    val expiresAt: Long
)
