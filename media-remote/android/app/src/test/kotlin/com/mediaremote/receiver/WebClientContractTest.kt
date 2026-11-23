package com.mediaremote.receiver

import com.mediaremote.receiver.protocol.*
import com.mediaremote.receiver.security.CryptoEngine
import kotlinx.serialization.Serializable
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.Base64

@Serializable
data class HmacVector(
    val name: String,
    val secretBase64: String,
    val message: String,
    val expectedHmacHex: String,
    val expectedHmacBase64: String
)

@Serializable
data class HmacVectorFile(
    val vectors: List<HmacVector>
)

class WebClientContractTest {

    private fun resolveFixturesDir(): File {
        val candidates = listOf(
            File("shared/fixtures"),
            File("../shared/fixtures"),
            File("../../shared/fixtures"),
            File("media-remote/shared/fixtures")
        )
        return candidates.firstOrNull { it.exists() && it.isDirectory }
            ?: error("Could not locate shared/fixtures directory from user.dir=${System.getProperty("user.dir")}")
    }

    private fun readFixture(name: String): String {
        val file = File(resolveFixturesDir(), name)
        assertTrue("Expected fixture file ${file.absolutePath} to exist", file.exists())
        return file.readText(Charsets.UTF_8)
    }

    private fun parseValidEnvelope(fixtureName: String): ProtocolEnvelope {
        val raw = readFixture(fixtureName)
        val result = ProtocolValidator.parseEnvelope(raw)
        assertTrue("Envelope validation failed for $fixtureName: $result", result is ValidationResult.Valid)
        return (result as ValidationResult.Valid).value
    }

    @Test
    fun `web client hello fixture decodes strictly with HelloPayload`() {
        val env = parseValidEnvelope("hello.json")
        assertEquals("hello", env.type)
        val payload = ProtocolValidator.json.decodeFromString(HelloPayload.serializer(), env.data.toString())
        assertTrue(ProtocolConstants.VERSION in payload.supportedVersions)
        assertTrue(payload.clientId.length in 8..64)
        assertTrue(payload.clientName.isNotBlank())
    }

    @Test
    fun `web client pair_request fixture decodes strictly with PairRequestPayload`() {
        val env = parseValidEnvelope("pair_request.json")
        assertEquals("pair_request", env.type)
        val payload = ProtocolValidator.json.decodeFromString(PairRequestPayload.serializer(), env.data.toString())
        assertTrue(payload.pairingToken.length >= 16)
    }

    @Test
    fun `web client auth_response fixture decodes strictly and verifies against CryptoEngine`() {
        val env = parseValidEnvelope("auth_response.json")
        assertEquals("auth_response", env.type)
        val payload = ProtocolValidator.json.decodeFromString(AuthResponsePayload.serializer(), env.data.toString())
        assertEquals("web-ab12cd34", payload.clientId)

        val fixedSecretB64 = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="
        val fixedServerNonce = "U2VydmVyTm9uY2UxMjM0NTY3ODkwMTIzNA=="
        val expected = CryptoEngine.hmacSha256(
            Base64.getDecoder().decode(fixedSecretB64),
            "$fixedServerNonce:${payload.clientNonce}:${payload.clientId}".toByteArray(Charsets.UTF_8)
        )
        assertEquals(Base64.getEncoder().encodeToString(expected), payload.proofHmac)
    }

    @Test
    fun `all web client command fixtures decode strictly and pass ProtocolValidator`() {
        val commandFiles = mapOf(
            "command_play.json" to "play",
            "command_pause.json" to "pause",
            "command_stop.json" to "stop",
            "command_next.json" to "next",
            "command_previous.json" to "previous",
            "command_set_volume.json" to "set_volume",
            "command_select_session.json" to "select_session"
        )

        for ((fileName, expectedAction) in commandFiles) {
            val env = parseValidEnvelope(fileName)
            assertEquals("command", env.type)
            val parsedCmd = ProtocolValidator.parseCommand(env)
            assertTrue("Command $fileName failed ProtocolValidator.parseCommand: $parsedCmd", parsedCmd is ValidationResult.Valid)
            val cmd = (parsedCmd as ValidationResult.Valid).value
            assertEquals(expectedAction, cmd.action)

            if (expectedAction == "set_volume") {
                assertEquals(65, cmd.volume)
            } else if (expectedAction == "select_session") {
                assertEquals("com.spotify.music:1042", cmd.targetSessionId)
            }
        }
    }

    @Test
    fun `web client ping and sync_request fixtures decode strictly`() {
        val pingEnv = parseValidEnvelope("ping.json")
        assertEquals("ping", pingEnv.type)
        val ping = ProtocolValidator.json.decodeFromString(PingPayload.serializer(), pingEnv.data.toString())
        assertTrue(ping.sentAt >= 1_700_000_000_000L)

        val syncEnv = parseValidEnvelope("sync_request.json")
        assertEquals("sync_request", syncEnv.type)
        val sync = ProtocolValidator.json.decodeFromString(SyncRequestPayload.serializer(), syncEnv.data.toString())
        assertTrue(sync.reason in setOf("initial", "reconnect", "manual"))
    }

    @Test
    fun `pure-JS HMAC fixtures match CryptoEngine hmacSha256 bit-for-bit`() {
        val raw = readFixture("hmac_vectors.json")
        val vectorFile = ProtocolValidator.json.decodeFromString(HmacVectorFile.serializer(), raw)
        assertTrue(vectorFile.vectors.isNotEmpty())

        for (vector in vectorFile.vectors) {
            val secretBytes = Base64.getDecoder().decode(vector.secretBase64)
            val digest = CryptoEngine.hmacSha256(secretBytes, vector.message.toByteArray(Charsets.UTF_8))
            val actualHex = digest.joinToString("") { "%02x".format(it) }
            val actualB64 = Base64.getEncoder().encodeToString(digest)
            assertEquals("Hex mismatch for vector ${vector.name}", vector.expectedHmacHex, actualHex)
            assertEquals("Base64 mismatch for vector ${vector.name}", vector.expectedHmacBase64, actualB64)
        }
    }

    @Test
    fun `state and state_last_played fixtures decode strictly with MediaStatePayload`() {
        val stateEnv = parseValidEnvelope("state.json")
        assertEquals("state", stateEnv.type)
        val statePayload = ProtocolValidator.json.decodeFromString(
            MediaStatePayload.serializer(),
            stateEnv.data.toString()
        )
        assertFalse(statePayload.isLastPlayed)
        assertEquals("Spotify", statePayload.appName)
        assertNotNull(statePayload.appIconB64)
        assertEquals(2, statePayload.sessions.size)
        assertEquals("Spotify", statePayload.sessions[0].appName)
        assertNull(statePayload.sessions[1].appName)

        val lastPlayedEnv = parseValidEnvelope("state_last_played.json")
        assertEquals("state", lastPlayedEnv.type)
        val lastPlayedPayload = ProtocolValidator.json.decodeFromString(
            MediaStatePayload.serializer(),
            lastPlayedEnv.data.toString()
        )
        assertTrue(lastPlayedPayload.isLastPlayed)
        assertNull(lastPlayedPayload.sessionId)
        assertEquals("STOPPED", lastPlayedPayload.playbackState)
        assertEquals(listOf("play"), lastPlayedPayload.supportedActions)
        assertEquals("Spotify", lastPlayedPayload.appName)
        assertNotNull(lastPlayedPayload.appIconB64)
    }

    @Test
    fun `unknown keys are strictly rejected by ProtocolValidator json`() {
        val badEnvelope = """
            {
              "v": 1,
              "type": "ping",
              "id": "req-12345678",
              "seq": 1,
              "ts": 1763712000000,
              "extraField": true,
              "data": { "sentAt": 1763712000000 }
            }
        """.trimIndent()
        val res = ProtocolValidator.parseEnvelope(badEnvelope)
        assertTrue(res is ValidationResult.Invalid)
        assertEquals("INVALID_SCHEMA", (res as ValidationResult.Invalid).code)

        val badCommandEnv = ProtocolValidator.parseEnvelope(
            """
            {
              "v": 1,
              "type": "command",
              "id": "req-12345678",
              "seq": 2,
              "ts": 1763712000000,
              "data": { "action": "play", "unknownProp": 123 }
            }
            """.trimIndent()
        ) as ValidationResult.Valid
        val cmdRes = ProtocolValidator.parseCommand(badCommandEnv.value)
        assertTrue(cmdRes is ValidationResult.Invalid)
        assertEquals("INVALID_SCHEMA", (cmdRes as ValidationResult.Invalid).code)

        val badStateJson = """
            {
              "deviceId": "and-pixel8",
              "deviceName": "Pixel 8",
              "packageName": "com.spotify.music",
              "playbackState": "NONE",
              "isLastPlayed": false,
              "supportedActions": [],
              "volume": { "current": 5, "max": 15, "percent": 33, "isFixed": false },
              "sessions": [],
              "unexpectedField": true
            }
        """.trimIndent()
        val stateDecode = runCatching {
            ProtocolValidator.json.decodeFromString(MediaStatePayload.serializer(), badStateJson)
        }
        assertTrue(stateDecode.isFailure)
    }
}
