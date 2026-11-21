package com.mediaremote.receiver.security

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

object CryptoEngine {
    private val rng = SecureRandom()
    private val b64Enc = Base64.getEncoder()
    private val b64Dec = Base64.getDecoder()

    fun generateEcdhKeyPair(): KeyPair {
        val kpg = KeyPairGenerator.getInstance("EC")
        kpg.initialize(ECGenParameterSpec("secp256r1"), rng)
        return kpg.generateKeyPair()
    }

    fun encodePublicKey(keyPair: KeyPair): String =
        b64Enc.encodeToString(keyPair.public.encoded)

    fun encodePrivateKey(keyPair: KeyPair): String =
        b64Enc.encodeToString(keyPair.private.encoded)

    fun deriveSharedSecret(localKeyPair: KeyPair, remotePublicKeyB64: String): ByteArray {
        val remoteBytes = b64Dec.decode(remotePublicKeyB64)
        val remotePub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(remoteBytes))
        val ka = KeyAgreement.getInstance("ECDH")
        ka.init(localKeyPair.private)
        ka.doPhase(remotePub, true)
        return ka.generateSecret()
    }

    /**
     * RFC 5869 HKDF-SHA256 key derivation for session encryption and HMAC keys.
     */
    fun hkdfSha256(ikm: ByteArray, salt: ByteArray, info: ByteArray, lengthBytes: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        val actualSalt = if (salt.isEmpty()) ByteArray(32) else salt
        mac.init(SecretKeySpec(actualSalt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val okm = ByteArray(lengthBytes)
        var t = ByteArray(0)
        var offset = 0
        var counter = 1
        while (offset < lengthBytes) {
            mac.init(SecretKeySpec(prk, "HmacSHA256"))
            mac.update(t)
            mac.update(info)
            mac.update(counter.toByte())
            t = mac.doFinal()
            val toCopy = minOf(t.size, lengthBytes - offset)
            System.arraycopy(t, 0, okm, offset, toCopy)
            offset += toCopy
            counter++
        }
        return okm
    }

    fun hmacSha256(key: ByteArray, message: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(message)
    }

    fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean =
        MessageDigest.isEqual(a, b)

    fun keyFingerprint(pubKeyB64: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(b64Dec.decode(pubKeyB64))
        return digest.take(8).joinToString("") { "%02x".format(it) }
    }

    fun randomTokenBase64(bytes: Int = 24): String {
        val buf = ByteArray(bytes)
        rng.nextBytes(buf)
        return b64Enc.encodeToString(buf)
    }

    data class AesGcmCiphertext(val ivB64: String, val ciphertextB64: String, val tagB64: String)

    fun encryptAesGcm(key32: ByteArray, plaintext: ByteArray, aad: ByteArray = ByteArray(0)): AesGcmCiphertext {
        val iv = ByteArray(12)
        rng.nextBytes(iv)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key32, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        val combined = cipher.doFinal(plaintext)
        val ctLen = combined.size - 16
        val ct = combined.copyOfRange(0, ctLen)
        val tag = combined.copyOfRange(ctLen, combined.size)
        return AesGcmCiphertext(
            ivB64 = b64Enc.encodeToString(iv),
            ciphertextB64 = b64Enc.encodeToString(ct),
            tagB64 = b64Enc.encodeToString(tag)
        )
    }

    fun decryptAesGcm(key32: ByteArray, ivB64: String, ctB64: String, tagB64: String, aad: ByteArray = ByteArray(0)): ByteArray {
        val iv = b64Dec.decode(ivB64)
        val ct = b64Dec.decode(ctB64)
        val tag = b64Dec.decode(tagB64)
        val combined = ByteArray(ct.size + tag.size)
        System.arraycopy(ct, 0, combined, 0, ct.size)
        System.arraycopy(tag, 0, combined, ct.size, tag.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key32, "AES"), GCMParameterSpec(128, iv))
        if (aad.isNotEmpty()) cipher.updateAAD(aad)
        return cipher.doFinal(combined)
    }
}
