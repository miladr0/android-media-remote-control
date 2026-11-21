package com.mediaremote.receiver.protocol

import kotlinx.serialization.json.Json
import kotlin.math.abs

object ProtocolConstants {
    const val VERSION = 1
    const val SERVICE_TYPE = "_mediaremote._tcp."
    const val DEFAULT_PORT = 8765
    const val REPLAY_WINDOW_MS = 30_000L
    const val PAIRING_TOKEN_TTL_MS = 120_000L
    val VALID_MESSAGE_TYPES = setOf(
        "hello", "hello_ack", "pair_request", "pair_ack",
        "auth_challenge", "auth_response", "auth_ok",
        "command", "ack", "state", "sync_request",
        "ping", "pong", "error", "encrypted"
    )
    val VALID_ACTIONS = setOf(
        "play", "pause", "stop", "next", "previous",
        "set_volume", "select_session"
    )
}

sealed class ValidationResult<out T> {
    data class Valid<T>(val value: T) : ValidationResult<T>()
    data class Invalid(val code: String, val message: String) : ValidationResult<Nothing>()
}

class ReplayGuard(private val windowMs: Long = ProtocolConstants.REPLAY_WINDOW_MS) {
    private var lastSeq: Long = -1L
    private val seenNonces = LinkedHashSet<String>()

    @Synchronized
    fun verify(seq: Long, ts: Long, nonce: String?, nowMs: Long = System.currentTimeMillis()): ValidationResult<Unit> {
        if (seq <= lastSeq) {
            return ValidationResult.Invalid("REPLAY_DETECTED", "Sequence $seq is not greater than last $lastSeq")
        }
        if (abs(nowMs - ts) > windowMs) {
            return ValidationResult.Invalid("REPLAY_DETECTED", "Timestamp outside ±${windowMs}ms freshness window")
        }
        if (nonce != null) {
            if (!seenNonces.add(nonce)) {
                return ValidationResult.Invalid("REPLAY_DETECTED", "Duplicate nonce rejected")
            }
            if (seenNonces.size > 512) {
                val oldest = seenNonces.first()
                seenNonces.remove(oldest)
            }
        }
        lastSeq = seq
        return ValidationResult.Valid(Unit)
    }
}

object ProtocolValidator {
    val json = Json {
        ignoreUnknownKeys = false
        encodeDefaults = true
        explicitNulls = true
    }

    fun parseEnvelope(raw: String): ValidationResult<ProtocolEnvelope> {
        val env = try {
            json.decodeFromString(ProtocolEnvelope.serializer(), raw)
        } catch (ex: Exception) {
            return ValidationResult.Invalid("INVALID_SCHEMA", "Malformed envelope JSON: ${ex.message}")
        }
        if (env.v != ProtocolConstants.VERSION) {
            return ValidationResult.Invalid("UNSUPPORTED_VERSION", "Protocol version ${env.v} is unsupported")
        }
        if (env.type !in ProtocolConstants.VALID_MESSAGE_TYPES) {
            return ValidationResult.Invalid("INVALID_SCHEMA", "Unknown message type: ${env.type}")
        }
        if (env.id.length !in 8..64 || env.seq < 0) {
            return ValidationResult.Invalid("INVALID_SCHEMA", "Invalid envelope id or sequence")
        }
        return ValidationResult.Valid(env)
    }

    fun parseCommand(env: ProtocolEnvelope): ValidationResult<CommandPayload> {
        val cmd = try {
            json.decodeFromString(CommandPayload.serializer(), env.data.toString())
        } catch (ex: Exception) {
            return ValidationResult.Invalid("INVALID_SCHEMA", "Malformed command payload: ${ex.message}")
        }
        if (cmd.action !in ProtocolConstants.VALID_ACTIONS) {
            return ValidationResult.Invalid("INVALID_SCHEMA", "Unsupported command action: ${cmd.action}")
        }
        if (cmd.action == "set_volume") {
            val vol = cmd.volume
            if (vol == null || vol !in 0..100) {
                return ValidationResult.Invalid("INVALID_SCHEMA", "set_volume requires integer volume 0..100")
            }
        }
        if (cmd.action == "select_session" && cmd.targetSessionId.isNullOrBlank()) {
            return ValidationResult.Invalid("INVALID_SCHEMA", "select_session requires targetSessionId")
        }
        return ValidationResult.Valid(cmd)
    }
}
