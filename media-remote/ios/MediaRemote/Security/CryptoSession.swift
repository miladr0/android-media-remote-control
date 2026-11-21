import CryptoKit
import Foundation

enum CryptoError: Error {
    case invalidKeyFormat
    case encryptionFailed
    case decryptionFailed
}

/// Interoperates with Android JCA X509EncodedKeySpec (SPKI) P-256 public keys and AES-256-GCM.
final class CryptoSession {
    // Standard ASN.1 SPKI header for EC P-256 uncompressed public keys (26 bytes)
    private static let p256SpkiHeader = Data([
        0x30, 0x59, 0x30, 0x13, 0x06, 0x07, 0x2A, 0x86,
        0x48, 0xCE, 0x3D, 0x02, 0x01, 0x06, 0x08, 0x2A,
        0x86, 0x48, 0xCE, 0x3D, 0x03, 0x01, 0x07, 0x03,
        0x42, 0x00
    ])

    static func generateKeyPair() -> P256.KeyAgreement.PrivateKey {
        P256.KeyAgreement.PrivateKey()
    }

    static func exportSpkiPublicKeyBase64(_ privateKey: P256.KeyAgreement.PrivateKey) -> String {
        let raw = privateKey.publicKey.x963Representation
        return (p256SpkiHeader + raw).base64EncodedString()
    }

    static func deriveSharedSecret(
        privateKey: P256.KeyAgreement.PrivateKey,
        remoteSpkiBase64: String
    ) throws -> Data {
        guard let spkiData = Data(base64Encoded: remoteSpkiBase64) else {
            throw CryptoError.invalidKeyFormat
        }
        let rawX963: Data
        if spkiData.starts(with: p256SpkiHeader) {
            rawX963 = spkiData.dropFirst(p256SpkiHeader.count)
        } else {
            rawX963 = spkiData
        }
        let remotePub = try P256.KeyAgreement.PublicKey(x963Representation: rawX963)
        let secret = try privateKey.sharedSecretFromKeyAgreement(with: remotePub)
        return secret.withUnsafeBytes { Data($0) }
    }

    static func deriveSessionKey(sharedSecret: Data, sessionSaltBase64: String) throws -> SymmetricKey {
        guard let salt = Data(base64Encoded: sessionSaltBase64) else {
            throw CryptoError.invalidKeyFormat
        }
        let ikm = SymmetricKey(data: sharedSecret)
        return HKDF<SHA256>.deriveKey(
            inputKeyMaterial: ikm,
            salt: salt,
            info: Data("MediaRemote-v1-Session".utf8),
            outputByteCount: 32
        )
    }

    static func hmacBase64(key: SymmetricKey, message: String) -> String {
        let mac = HMAC<SHA256>.authenticationCode(for: Data(message.utf8), using: key)
        return Data(mac).base64EncodedString()
    }

    static func verifyHmac(key: SymmetricKey, message: String, expectedBase64: String) -> Bool {
        guard let expected = Data(base64Encoded: expectedBase64) else { return false }
        return HMAC<SHA256>.isValidAuthenticationCode(expected, authenticating: Data(message.utf8), using: key)
    }

    static func encryptAesGcm(key: SymmetricKey, plaintext: Data) throws -> EncryptedPayload {
        let sealed = try AES.GCM.seal(plaintext, using: key)
        let iv = Data(sealed.nonce)
        return EncryptedPayload(
            iv: iv.base64EncodedString(),
            ciphertext: sealed.ciphertext.base64EncodedString(),
            tag: sealed.tag.base64EncodedString()
        )
    }

    static func decryptAesGcm(key: SymmetricKey, payload: EncryptedPayload) throws -> Data {
        guard
            let iv = Data(base64Encoded: payload.iv),
            let ct = Data(base64Encoded: payload.ciphertext),
            let tag = Data(base64Encoded: payload.tag)
        else {
            throw CryptoError.decryptionFailed
        }
        let nonce = try AES.GCM.Nonce(data: iv)
        let box = try AES.GCM.SealedBox(nonce: nonce, ciphertext: ct, tag: tag)
        return try AES.GCM.open(box, using: key)
    }

    static func randomBase64(bytes: Int = 24) -> String {
        var data = Data(count: bytes)
        _ = data.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, bytes, $0.baseAddress!) }
        return data.base64EncodedString()
    }
}

final class InboundReplayGuard {
    private var lastSeq: Int64 = -1
    private var seenNonces = Set<String>()
    private let windowMs: Int64

    init(windowMs: Int64 = ProtocolConstants.replayWindowMs) {
        self.windowMs = windowMs
    }

    func verify(seq: Int64, ts: Int64, nonce: String?, nowMs: Int64 = Int64(Date().timeIntervalSince1970 * 1000)) -> Bool {
        guard seq > lastSeq else { return false }
        guard abs(nowMs - ts) <= windowMs else { return false }
        if let nonce {
            guard !seenNonces.contains(nonce) else { return false }
            seenNonces.insert(nonce)
            if seenNonces.count > 512, let first = seenNonces.first {
                seenNonces.remove(first)
            }
        }
        lastSeq = seq
        return true
    }

    func reset() {
        lastSeq = -1
        seenNonces.removeAll()
    }
}
