package com.mediaremote.receiver

import com.mediaremote.receiver.protocol.ProtocolValidator
import com.mediaremote.receiver.protocol.ValidationResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtocolValidatorTest {

    @Test
    fun `valid play command passes validation`() {
        val raw = """
            {
              "v": 1,
              "type": "command",
              "id": "req-12345678",
              "seq": 1,
              "ts": 1760000000000,
              "data": { "action": "play" }
            }
        """.trimIndent()
        val env = ProtocolValidator.parseEnvelope(raw)
        assertTrue(env is ValidationResult.Valid)
        val cmd = ProtocolValidator.parseCommand((env as ValidationResult.Valid).value)
        assertTrue(cmd is ValidationResult.Valid)
        assertEquals("play", (cmd as ValidationResult.Valid).value.action)
    }

    @Test
    fun `unsupported protocol version is rejected`() {
        val raw = """
            {
              "v": 2,
              "type": "hello",
              "id": "req-12345678",
              "seq": 0,
              "ts": 1760000000000,
              "data": { "clientId": "ios-12345", "clientName": "iPhone", "supportedVersions": [2] }
            }
        """.trimIndent()
        val res = ProtocolValidator.parseEnvelope(raw)
        assertTrue(res is ValidationResult.Invalid)
        assertEquals("UNSUPPORTED_VERSION", (res as ValidationResult.Invalid).code)
    }

    @Test
    fun `set_volume without volume field is rejected`() {
        val raw = """
            {
              "v": 1,
              "type": "command",
              "id": "req-12345678",
              "seq": 2,
              "ts": 1760000000000,
              "data": { "action": "set_volume" }
            }
        """.trimIndent()
        val env = ProtocolValidator.parseEnvelope(raw) as ValidationResult.Valid
        val cmd = ProtocolValidator.parseCommand(env.value)
        assertTrue(cmd is ValidationResult.Invalid)
        assertEquals("INVALID_SCHEMA", (cmd as ValidationResult.Invalid).code)
    }
}
