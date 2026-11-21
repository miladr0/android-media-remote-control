package com.mediaremote.receiver

import com.mediaremote.receiver.protocol.ReplayGuard
import com.mediaremote.receiver.protocol.ValidationResult
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayProtectionTest {

    @Test
    fun `monotonic sequence and unique nonces pass while replays fail`() {
        val guard = ReplayGuard(windowMs = 30_000L)
        val now = 1_760_000_000_000L

        assertTrue(guard.verify(seq = 1, ts = now, nonce = "nonce-a-123456", nowMs = now) is ValidationResult.Valid)
        assertTrue(guard.verify(seq = 1, ts = now, nonce = "nonce-b-123456", nowMs = now) is ValidationResult.Invalid)
        assertTrue(guard.verify(seq = 2, ts = now, nonce = "nonce-a-123456", nowMs = now) is ValidationResult.Invalid)
        assertTrue(guard.verify(seq = 3, ts = now - 60_000L, nonce = "nonce-c-123456", nowMs = now) is ValidationResult.Invalid)
        assertTrue(guard.verify(seq = 2, ts = now, nonce = "nonce-d-123456", nowMs = now) is ValidationResult.Valid)
    }
}
