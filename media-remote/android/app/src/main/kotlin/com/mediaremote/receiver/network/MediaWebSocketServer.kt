package com.mediaremote.receiver.network

import android.content.Context
import com.mediaremote.receiver.media.CommandDispatchResult
import com.mediaremote.receiver.media.MediaSessionCoordinator
import com.mediaremote.receiver.protocol.*
import com.mediaremote.receiver.security.CryptoEngine
import com.mediaremote.receiver.security.PairingManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.server.websocket.*
import io.ktor.websocket.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.util.Base64
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

data class ActiveClientSession(
    val connectionId: String,
    val socket: DefaultWebSocketServerSession,
    var clientId: String? = null,
    var clientName: String? = null,
    var authenticated: Boolean = false,
    var pendingServerNonce: String? = null,
    var pendingSessionSalt: String? = null,
    val replayGuard: ReplayGuard = ReplayGuard(),
    val outboundSeq: AtomicLong = AtomicLong(1L)
)

class MediaWebSocketServer(
    private val context: Context,
    private val port: Int,
    private val pairingManager: PairingManager,
    private val coordinator: MediaSessionCoordinator
) {
    private val json = ProtocolValidator.json
    private var scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var engine: ApplicationEngine? = null
    private val sessions = ConcurrentHashMap<String, ActiveClientSession>()

    private val _connectedClients = MutableStateFlow<List<String>>(emptyList())
    val connectedClients: StateFlow<List<String>> = _connectedClients.asStateFlow()

    private val _activeConnectionCount = MutableStateFlow(0)
    val activeConnectionCount: StateFlow<Int> = _activeConnectionCount.asStateFlow()

    @Synchronized
    fun start() {
        if (engine != null) return
        if (!scope.isActive) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        }
        sessions.clear()
        updateConnectedSummary()

        engine = embeddedServer(CIO, port = port, host = "0.0.0.0") {
            install(WebSockets) {
                pingPeriod = Duration.ofSeconds(15)
                timeout = Duration.ofSeconds(30)
                maxFrameSize = 2 * 1024 * 1024
                masking = false
            }
            routing {
                get("/") {
                    serveAsset(call, "web/index.html", ContentType.Text.Html.withCharset(Charsets.UTF_8))
                }
                get("/manifest.webmanifest") {
                    serveAsset(call, "web/manifest.webmanifest", ContentType("application", "manifest+json"))
                }
                get("/apple-touch-icon.png") {
                    serveAsset(call, "web/apple-touch-icon.png", ContentType.Image.PNG)
                }
                get("/icon-192.png") {
                    serveAsset(call, "web/icon-192.png", ContentType.Image.PNG)
                }
                get("/icon-512.png") {
                    serveAsset(call, "web/icon-512.png", ContentType.Image.PNG)
                }
                webSocket("/mediaremote") {
                    handleConnection(this)
                }
            }
        }.start(wait = false)

        scope.launch {
            coordinator.state.collect { statePayload ->
                broadcastState(statePayload)
            }
        }
    }

    private suspend fun serveAsset(call: ApplicationCall, assetPath: String, contentType: ContentType) {
        val bytes = runCatching {
            context.assets.open(assetPath).use { it.readBytes() }
        }.getOrNull()
        if (bytes == null) {
            call.respond(HttpStatusCode.NotFound, "Asset not found")
            return
        }
        call.respondBytes(bytes, contentType)
    }

    @Synchronized
    fun stop() {
        val toClose = sessions.values.toList()
        sessions.clear()
        updateConnectedSummary()
        runBlocking {
            withTimeoutOrNull(800L) {
                toClose.forEach { session ->
                    runCatching {
                        session.socket.close(CloseReason(CloseReason.Codes.GOING_AWAY, "Server stopped"))
                    }
                    runCatching { session.socket.cancel() }
                }
            }
        }
        scope.cancel()
        runCatching { engine?.stop(300, 1000) }
        engine = null
    }

    fun disconnectClient(clientId: String, onComplete: (() -> Unit)? = null) {
        val targets = sessions.values.filter { it.clientId == clientId }
        targets.forEach { active ->
            sessions.remove(active.connectionId)
            active.authenticated = false
            active.pendingServerNonce = null
            active.pendingSessionSalt = null
        }
        updateConnectedSummary()

        if (targets.isEmpty()) {
            onComplete?.invoke()
            return
        }

        scope.launch {
            try {
                targets.forEach { active ->
                    withTimeoutOrNull(700L) {
                        runCatching {
                            sendError(
                                active,
                                null,
                                "DEVICE_REVOKED",
                                "Pairing revoked on Android host",
                                recoverable = false
                            )
                        }
                        runCatching {
                            active.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Revoked"))
                        }
                    }
                    runCatching { active.socket.cancel() }
                }
            } finally {
                onComplete?.invoke()
            }
        }
    }

    private suspend fun handleConnection(ws: DefaultWebSocketServerSession) {
        val connId = UUID.randomUUID().toString()
        val session = ActiveClientSession(connectionId = connId, socket = ws)
        sessions[connId] = session
        updateConnectedSummary()

        try {
            for (frame in ws.incoming) {
                if (frame is Frame.Text) {
                    handleIncomingText(session, frame.readText())
                }
            }
        } finally {
            sessions.remove(connId)
            updateConnectedSummary()
        }
    }

    private suspend fun handleIncomingText(session: ActiveClientSession, rawText: String) {
        val parsed = ProtocolValidator.parseEnvelope(rawText)
        if (parsed is ValidationResult.Invalid) {
            sendError(session, null, parsed.code, parsed.message, recoverable = true)
            return
        }
        val env = (parsed as ValidationResult.Valid).value

        // Enforce replay protection on all frames post-hello
        if (env.type != "hello") {
            val replay = session.replayGuard.verify(env.seq, env.ts, env.nonce)
            if (replay is ValidationResult.Invalid) {
                sendError(session, env.id, replay.code, replay.message, recoverable = false)
                return
            }
        }

        when (env.type) {
            "hello" -> handleHello(session, env)
            "pair_request" -> handlePairRequest(session, env)
            "auth_response" -> handleAuthResponse(session, env)
            "ping" -> handlePing(session, env)
            "sync_request" -> handleSyncRequest(session, env)
            "command" -> handleCommand(session, env)
            else -> sendError(session, env.id, "INVALID_SCHEMA", "Unhandled type ${env.type}", recoverable = true)
        }
    }

    private suspend fun handleHello(session: ActiveClientSession, env: ProtocolEnvelope) {
        val hello = runCatching { json.decodeFromString(HelloPayload.serializer(), env.data.toString()) }.getOrNull()
            ?: return sendError(session, env.id, "INVALID_SCHEMA", "Invalid hello payload", recoverable = false)

        if (ProtocolConstants.VERSION !in hello.supportedVersions) {
            sendError(session, env.id, "UNSUPPORTED_VERSION", "Requires protocol v1", recoverable = false)
            session.socket.close(CloseReason(CloseReason.Codes.PROTOCOL_ERROR, "Unsupported version"))
            return
        }

        session.authenticated = false
        session.pendingServerNonce = null
        session.pendingSessionSalt = null
        session.clientId = hello.clientId
        session.clientName = hello.clientName
        updateConnectedSummary()

        val existing = pairingManager.getPairedClient(hello.clientId)
        val ack = HelloAckPayload(
            selectedVersion = ProtocolConstants.VERSION,
            deviceId = pairingManager.deviceId,
            deviceName = pairingManager.deviceName,
            requiresPairing = existing == null,
            serverTime = System.currentTimeMillis()
        )
        sendFrame(session, "hello_ack", json.encodeToJsonElement(ack).jsonObject)

        if (existing != null) {
            issueAuthChallenge(session)
        }
    }

    private suspend fun handlePairRequest(session: ActiveClientSession, env: ProtocolEnvelope) {
        if (session.authenticated) {
            sendError(session, env.id, "UNAUTHENTICATED", "Session is already authenticated", recoverable = false)
            return
        }
        val req = runCatching { json.decodeFromString(PairRequestPayload.serializer(), env.data.toString()) }.getOrNull()
            ?: return sendError(session, env.id, "INVALID_SCHEMA", "Invalid pair_request", recoverable = false)

        if (session.clientId != null && req.clientId != session.clientId) {
            sendError(session, env.id, "UNAUTHENTICATED", "pair_request clientId does not match hello", recoverable = false)
            session.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Client ID mismatch"))
            return
        }

        val result = pairingManager.completePairing(req.clientId, req.clientName, req.pairingToken)
        if (result.isFailure) {
            val code = result.exceptionOrNull()?.message ?: "INVALID_TOKEN"
            sendError(session, env.id, code, "Pairing rejected: $code", recoverable = false)
            return
        }

        val record = result.getOrThrow()
        session.clientId = req.clientId
        session.clientName = req.clientName
        val pairAck = PairAckPayload(
            deviceId = pairingManager.deviceId,
            deviceName = pairingManager.deviceName,
            deviceSecret = record.sharedSecretB64,
            keyFingerprint = record.keyFingerprint
        )
        sendFrame(session, "pair_ack", json.encodeToJsonElement(pairAck).jsonObject)
        issueAuthChallenge(session)
    }

    private suspend fun issueAuthChallenge(session: ActiveClientSession) {
        val serverNonce = CryptoEngine.randomTokenBase64(24)
        val sessionSalt = CryptoEngine.randomTokenBase64(24)
        session.pendingServerNonce = serverNonce
        session.pendingSessionSalt = sessionSalt
        val challenge = AuthChallengePayload(serverNonce = serverNonce, sessionSalt = sessionSalt)
        sendFrame(session, "auth_challenge", json.encodeToJsonElement(challenge).jsonObject)
    }

    private suspend fun handleAuthResponse(session: ActiveClientSession, env: ProtocolEnvelope) {
        if (session.authenticated) {
            session.pendingServerNonce = null
            session.pendingSessionSalt = null
            sendError(session, env.id, "UNAUTHENTICATED", "Session is already authenticated", recoverable = false)
            return
        }

        // Consume the single-use challenge immediately on any auth_response attempt (success or failure)
        val serverNonce = session.pendingServerNonce
        session.pendingServerNonce = null
        session.pendingSessionSalt = null

        val resp = runCatching { json.decodeFromString(AuthResponsePayload.serializer(), env.data.toString()) }.getOrNull()
            ?: run {
                sendError(session, env.id, "INVALID_SCHEMA", "Invalid auth_response", recoverable = false)
                session.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Invalid auth_response"))
                return
            }

        val boundClientId = session.clientId
        if (boundClientId == null || resp.clientId != boundClientId) {
            sendError(session, env.id, "UNAUTHENTICATED", "clientId differs from hello/pair_request", recoverable = false)
            session.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Client ID mismatch"))
            return
        }

        if (serverNonce == null) {
            sendError(session, env.id, "UNAUTHENTICATED", "No active authentication challenge", recoverable = false)
            session.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Missing challenge"))
            return
        }

        val record = pairingManager.getPairedClient(resp.clientId)
            ?: run {
                sendError(session, env.id, "DEVICE_REVOKED", "Client is not paired", recoverable = false)
                session.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Not paired"))
                return
            }

        val secretBytes = Base64.getDecoder().decode(record.sharedSecretB64)
        val expectedClientProof = CryptoEngine.hmacSha256(
            secretBytes,
            "$serverNonce:${resp.clientNonce}:${resp.clientId}".toByteArray()
        )
        val actualProof = runCatching { Base64.getDecoder().decode(resp.proofHmac) }.getOrNull()
        if (actualProof == null || !CryptoEngine.constantTimeEquals(expectedClientProof, actualProof)) {
            sendError(session, env.id, "UNAUTHENTICATED", "Challenge proof mismatch", recoverable = false)
            session.socket.close(CloseReason(CloseReason.Codes.VIOLATED_POLICY, "Auth failed"))
            return
        }

        val serverProof = CryptoEngine.hmacSha256(
            secretBytes,
            "${resp.clientNonce}:$serverNonce:${pairingManager.deviceId}".toByteArray()
        )
        session.authenticated = true
        updateConnectedSummary()

        val ok = AuthOkPayload(
            clientId = resp.clientId,
            serverProofHmac = Base64.getEncoder().encodeToString(serverProof),
            encryptionEnabled = false
        )
        sendFrame(session, "auth_ok", json.encodeToJsonElement(ok).jsonObject)
        sendFrame(session, "state", json.encodeToJsonElement(coordinator.state.value).jsonObject)
    }

    private suspend fun handlePing(session: ActiveClientSession, env: ProtocolEnvelope) {
        val ping = runCatching { json.decodeFromString(PingPayload.serializer(), env.data.toString()) }.getOrNull()
            ?: return sendError(session, env.id, "INVALID_SCHEMA", "Invalid ping payload", recoverable = true)
        val pong = PongPayload(echoSentAt = ping.sentAt, serverTime = System.currentTimeMillis())
        sendFrame(session, "pong", json.encodeToJsonElement(pong).jsonObject)
    }

    private suspend fun handleSyncRequest(session: ActiveClientSession, env: ProtocolEnvelope) {
        if (!session.authenticated) {
            sendError(session, env.id, "UNAUTHENTICATED", "Authentication required", recoverable = false)
            return
        }
        val sync = runCatching { json.decodeFromString(SyncRequestPayload.serializer(), env.data.toString()) }.getOrNull()
            ?: return sendError(session, env.id, "INVALID_SCHEMA", "Invalid sync_request payload", recoverable = true)
        if (sync.reason !in setOf("initial", "reconnect", "manual")) {
            sendError(session, env.id, "INVALID_SCHEMA", "Unsupported sync_request reason: ${sync.reason}", recoverable = true)
            return
        }
        sendFrame(session, "state", json.encodeToJsonElement(coordinator.state.value).jsonObject)
    }

    private suspend fun handleCommand(session: ActiveClientSession, env: ProtocolEnvelope) {
        if (!session.authenticated) {
            sendError(session, env.id, "UNAUTHENTICATED", "Commands require authenticated session", recoverable = false)
            return
        }
        val parsed = ProtocolValidator.parseCommand(env)
        if (parsed is ValidationResult.Invalid) {
            sendError(session, env.id, parsed.code, parsed.message, recoverable = true)
            return
        }
        val cmd = (parsed as ValidationResult.Valid).value
        when (val result = coordinator.executeCommand(cmd.action, cmd.volume, cmd.targetSessionId)) {
            is CommandDispatchResult.Accepted -> {
                val ack = AckPayload(requestId = env.id, action = cmd.action, status = "accepted")
                sendFrame(session, "ack", json.encodeToJsonElement(ack).jsonObject)
            }
            is CommandDispatchResult.Rejected -> {
                val ack = AckPayload(requestId = env.id, action = cmd.action, status = "rejected", reason = result.message)
                sendFrame(session, "ack", json.encodeToJsonElement(ack).jsonObject)
                sendError(session, env.id, result.code, result.message, recoverable = true)
            }
        }
    }

    private suspend fun broadcastState(state: MediaStatePayload) {
        val payload = json.encodeToJsonElement(state).jsonObject
        sessions.values.filter { it.authenticated }.forEach { session ->
            runCatching { sendFrame(session, "state", payload) }
        }
    }

    private suspend fun sendError(
        session: ActiveClientSession,
        requestId: String?,
        code: String,
        message: String,
        recoverable: Boolean
    ) {
        val err = ErrorPayload(requestId = requestId, code = code, message = message, recoverable = recoverable)
        sendFrame(session, "error", json.encodeToJsonElement(err).jsonObject)
    }

    private suspend fun sendFrame(
        session: ActiveClientSession,
        type: String,
        data: JsonObject
    ) {
        val envelope = ProtocolEnvelope(
            v = ProtocolConstants.VERSION,
            type = type,
            id = UUID.randomUUID().toString(),
            seq = session.outboundSeq.getAndIncrement(),
            ts = System.currentTimeMillis(),
            nonce = CryptoEngine.randomTokenBase64(12),
            data = data
        )
        session.socket.send(Frame.Text(json.encodeToString(envelope)))
    }

    private fun updateConnectedSummary() {
        _connectedClients.value = sessions.values
            .filter { it.authenticated }
            .mapNotNull { it.clientName ?: it.clientId }
        _activeConnectionCount.value = sessions.size
    }
}
