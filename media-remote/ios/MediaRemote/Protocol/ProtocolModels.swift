import Foundation

enum ProtocolConstants {
    static let version = 1
    static let bonjourType = "_mediaremote._tcp"
    static let defaultPort = 8765
    static let replayWindowMs: Int64 = 30_000
    static let heartbeatIntervalSeconds: TimeInterval = 10
    static let heartbeatTimeoutSeconds: TimeInterval = 25
}

struct ProtocolEnvelope<T: Codable>: Codable {
    let v: Int
    let type: String
    let id: String
    let seq: Int64
    let ts: Int64
    let nonce: String?
    let data: T
}

struct EnvelopeHeader: Codable {
    let v: Int
    let type: String
    let id: String
    let seq: Int64
    let ts: Int64
    let nonce: String?
}

struct HelloPayload: Codable {
    let clientId: String
    let clientName: String
    let supportedVersions: [Int]
}

struct HelloAckPayload: Codable {
    let selectedVersion: Int
    let deviceId: String
    let deviceName: String
    let requiresPairing: Bool
}

struct PairRequestPayload: Codable {
    let clientId: String
    let clientName: String
    let pairingToken: String
    let clientPubKey: String
}

struct PairAckPayload: Codable {
    let deviceId: String
    let devicePubKey: String
    let keyFingerprint: String
}

struct AuthChallengePayload: Codable {
    let serverNonce: String
    let sessionSalt: String
}

struct AuthResponsePayload: Codable {
    let clientId: String
    let clientNonce: String
    let proofHmac: String
}

struct AuthOkPayload: Codable {
    let clientId: String
    let serverProofHmac: String
    let encryptionEnabled: Bool
}

struct CommandPayload: Codable {
    let action: String
    let targetSessionId: String?
    let volume: Int?
}

struct AckPayload: Codable {
    let requestId: String
    let action: String
    let status: String
    let reason: String?
}

struct VolumeState: Codable, Equatable {
    let current: Int
    let max: Int
    let percent: Int
    let isFixed: Bool
}

struct SessionSummary: Codable, Identifiable, Equatable {
    var id: String { sessionId }
    let sessionId: String
    let packageName: String
    let appName: String
    let title: String?
    let playbackState: String
    let isSelected: Bool
}

struct MediaStatePayload: Codable, Equatable {
    let deviceId: String
    let deviceName: String
    let sessionId: String?
    let packageName: String?
    let appName: String?
    let title: String?
    let artist: String?
    let album: String?
    let durationMs: Int64?
    let positionMs: Int64?
    let playbackState: String
    let supportedActions: [String]
    let artworkBase64: String?
    let artworkHash: String?
    let volume: VolumeState
    let sessions: [SessionSummary]
}

struct ErrorPayload: Codable {
    let requestId: String?
    let code: String
    let message: String
    let recoverable: Bool
}

struct PingPayload: Codable {
    let sentAt: Int64
}

struct PongPayload: Codable {
    let echoSentAt: Int64
    let serverTime: Int64
}

struct SyncRequestPayload: Codable {
    let reason: String
}

struct EncryptedPayload: Codable {
    let iv: String
    let ciphertext: String
    let tag: String
}

struct PairingQrPayload: Codable {
    let v: Int
    let deviceId: String
    let deviceName: String
    let ip: String
    let port: Int
    let pairingToken: String
    let devicePubKey: String
    let expiresAt: Int64
}
