import CryptoKit
import Foundation
import Network
import UIKit

enum ConnectionStatus: Equatable {
    case disconnected
    case connecting
    case pairing
    case authenticating
    case connected
    case reconnecting(attempt: Int)
    case error(String)
}

struct ReconnectPolicy {
    static func delaySeconds(forAttempt attempt: Int) -> TimeInterval {
        let clamped = max(1, min(attempt, 6))
        return min(pow(2.0, Double(clamped - 1)), 16.0)
    }
}

final class RemoteWebSocketClient: NSObject, URLSessionWebSocketDelegate {
    var onStatusChange: ((ConnectionStatus) -> Void)?
    var onStateReceived: ((MediaStatePayload) -> Void)?
    var onAckReceived: ((AckPayload) -> Void)?
    var onErrorReceived: ((ErrorPayload) -> Void)?
    var onDevicePaired: ((PairedAndroidDevice) -> Void)?

    private var urlSession: URLSession?
    private var task: URLSessionWebSocketTask?
    private let pathMonitor = NWPathMonitor()
    private let monitorQueue = DispatchQueue(label: "com.mediaremote.path")

    private var targetHost: String?
    private var targetPort: Int = ProtocolConstants.defaultPort
    private var activePairingQr: PairingQrPayload?
    private var ephemeralPrivateKey: P256.KeyAgreement.PrivateKey?
    private var pairedDevice: PairedAndroidDevice?
    private var sessionKey: SymmetricKey?
    private var encryptionEnabled = false

    private var outboundSeq: Int64 = 1
    private let replayGuard = InboundReplayGuard()
    private var heartbeatTimer: Timer?
    private var reconnectWorkItem: DispatchWorkItem?
    private var reconnectAttempt = 0
    private var manualDisconnect = false

    private let clientId: String
    private let clientName = UIDevice.current.name
    private let encoder = JSONEncoder()
    private let decoder = JSONDecoder()

    init(clientId: String) {
        self.clientId = clientId
        super.init()
        startNetworkMonitor()
    }

    func connect(pairedDevice: PairedAndroidDevice) {
        manualDisconnect = false
        self.pairedDevice = pairedDevice
        self.activePairingQr = nil
        openSocket(host: pairedDevice.host, port: pairedDevice.port)
    }

    func pairAndConnect(qr: PairingQrPayload) {
        manualDisconnect = false
        self.pairedDevice = nil
        self.activePairingQr = qr
        self.ephemeralPrivateKey = CryptoSession.generateKeyPair()
        openSocket(host: qr.ip, port: qr.port)
    }

    func disconnect() {
        manualDisconnect = true
        reconnectWorkItem?.cancel()
        heartbeatTimer?.invalidate()
        task?.cancel(with: .normalClosure, reason: nil)
        task = nil
        sessionKey = nil
        encryptionEnabled = false
        onStatusChange?(.disconnected)
    }

    func sendCommand(_ payload: CommandPayload) -> String? {
        guard let task, sessionKey != nil else { return nil }
        let reqId = UUID().uuidString.lowercased()
        sendEnvelope(type: "command", id: reqId, data: payload, task: task)
        return reqId
    }

    private func openSocket(host: String, port: Int) {
        reconnectWorkItem?.cancel()
        heartbeatTimer?.invalidate()
        task?.cancel(with: .goingAway, reason: nil)
        sessionKey = nil
        encryptionEnabled = false
        outboundSeq = 1
        replayGuard.reset()

        targetHost = host
        targetPort = port
        onStatusChange?(reconnectAttempt > 0 ? .reconnecting(attempt: reconnectAttempt) : .connecting)

        guard let url = URL(string: "ws://\(host):\(port)/mediaremote") else {
            onStatusChange?(.error("Invalid WebSocket address"))
            return
        }

        let config = URLSessionConfiguration.default
        config.waitsForConnectivity = false
        let session = URLSession(configuration: config, delegate: self, delegateQueue: .main)
        urlSession = session
        let ws = session.webSocketTask(with: url)
        task = ws
        ws.resume()
        receiveLoop(for: ws)
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didOpenWithProtocol protocol: String?) {
        reconnectAttempt = 0
        let hello = HelloPayload(clientId: clientId, clientName: clientName, supportedVersions: [ProtocolConstants.version])
        sendEnvelope(type: "hello", id: UUID().uuidString.lowercased(), data: hello, task: webSocketTask)
    }

    func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
        handleDisconnect()
    }

    func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: Error?) {
        if error != nil {
            handleDisconnect()
        }
    }

    private func receiveLoop(for ws: URLSessionWebSocketTask) {
        ws.receive { [weak self] result in
            guard let self else { return }
            switch result {
            case .success(let message):
                if case .string(let text) = message {
                    self.handleIncomingRaw(text, ws: ws)
                }
                self.receiveLoop(for: ws)
            case .failure:
                self.handleDisconnect()
            }
        }
    }

    private func handleIncomingRaw(_ text: String, ws: URLSessionWebSocketTask) {
        guard let rawData = text.data(using: .utf8),
              let header = try? decoder.decode(EnvelopeHeader.self, from: rawData) else { return }

        var effectiveData = rawData
        var effectiveHeader = header

        if header.type == "encrypted", let key = sessionKey,
           let env = try? decoder.decode(ProtocolEnvelope<EncryptedPayload>.self, from: rawData),
           let decrypted = try? CryptoSession.decryptAesGcm(key: key, payload: env.data),
           let innerHeader = try? decoder.decode(EnvelopeHeader.self, from: decrypted) {
            effectiveData = decrypted
            effectiveHeader = innerHeader
        }

        guard effectiveHeader.v == ProtocolConstants.version else { return }
        if effectiveHeader.type != "hello_ack" {
            guard replayGuard.verify(seq: effectiveHeader.seq, ts: effectiveHeader.ts, nonce: effectiveHeader.nonce) else {
                return
            }
        }

        switch effectiveHeader.type {
        case "hello_ack":
            if let env = try? decoder.decode(ProtocolEnvelope<HelloAckPayload>.self, from: effectiveData) {
                if env.data.requiresPairing, let qr = activePairingQr, let priv = ephemeralPrivateKey {
                    onStatusChange?(.pairing)
                    let req = PairRequestPayload(
                        clientId: clientId,
                        clientName: clientName,
                        pairingToken: qr.pairingToken,
                        clientPubKey: CryptoSession.exportSpkiPublicKeyBase64(priv)
                    )
                    sendEnvelope(type: "pair_request", id: UUID().uuidString.lowercased(), data: req, task: ws)
                } else {
                    onStatusChange?(.authenticating)
                }
            }

        case "pair_ack":
            if let env = try? decoder.decode(ProtocolEnvelope<PairAckPayload>.self, from: effectiveData),
               let priv = ephemeralPrivateKey,
               let host = targetHost {
                if let secret = try? CryptoSession.deriveSharedSecret(privateKey: priv, remoteSpkiBase64: env.data.devicePubKey) {
                    let device = PairedAndroidDevice(
                        deviceId: env.data.deviceId,
                        deviceName: activePairingQr?.deviceName ?? "Android Device",
                        host: host,
                        port: targetPort,
                        sharedSecretBase64: secret.base64EncodedString(),
                        lastConnectedAt: Date()
                    )
                    self.pairedDevice = device
                    self.activePairingQr = nil
                    onDevicePaired?(device)
                }
            }

        case "auth_challenge":
            if let env = try? decoder.decode(ProtocolEnvelope<AuthChallengePayload>.self, from: effectiveData),
               let paired = pairedDevice,
               let secretData = Data(base64Encoded: paired.sharedSecretBase64),
               let derivedKey = try? CryptoSession.deriveSessionKey(sharedSecret: secretData, sessionSaltBase64: env.data.sessionSalt) {
                self.sessionKey = derivedKey
                let clientNonce = CryptoSession.randomBase64(bytes: 24)
                let proof = CryptoSession.hmacBase64(key: derivedKey, message: "\(env.data.serverNonce):\(clientNonce):\(clientId)")
                let resp = AuthResponsePayload(clientId: clientId, clientNonce: clientNonce, proofHmac: proof)
                sendEnvelope(type: "auth_response", id: UUID().uuidString.lowercased(), data: resp, task: ws)
            }

        case "auth_ok":
            if let env = try? decoder.decode(ProtocolEnvelope<AuthOkPayload>.self, from: effectiveData) {
                self.encryptionEnabled = env.data.encryptionEnabled
                onStatusChange?(.connected)
                startHeartbeat(ws: ws)
                sendEnvelope(type: "sync_request", id: UUID().uuidString.lowercased(), data: SyncRequestPayload(reason: "initial"), task: ws)
            }

        case "state":
            if let env = try? decoder.decode(ProtocolEnvelope<MediaStatePayload>.self, from: effectiveData) {
                onStateReceived?(env.data)
            }

        case "ack":
            if let env = try? decoder.decode(ProtocolEnvelope<AckPayload>.self, from: effectiveData) {
                onAckReceived?(env.data)
            }

        case "error":
            if let env = try? decoder.decode(ProtocolEnvelope<ErrorPayload>.self, from: effectiveData) {
                onErrorReceived?(env.data)
                if !env.data.recoverable {
                    onStatusChange?(.error(env.data.message))
                }
            }

        default:
            break
        }
    }

    private func sendEnvelope<T: Codable>(type: String, id: String, data: T, task: URLSessionWebSocketTask) {
        let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
        let seq = outboundSeq
        outboundSeq += 1

        let inner = ProtocolEnvelope(
            v: ProtocolConstants.version,
            type: type,
            id: id,
            seq: seq,
            ts: nowMs,
            nonce: CryptoSession.randomBase64(bytes: 12),
            data: data
        )
        guard let innerData = try? encoder.encode(inner) else { return }

        let wireData: Data
        if encryptionEnabled, let key = sessionKey, let enc = try? CryptoSession.encryptAesGcm(key: key, plaintext: innerData) {
            let outerSeq = outboundSeq
            outboundSeq += 1
            let outer = ProtocolEnvelope(
                v: ProtocolConstants.version,
                type: "encrypted",
                id: UUID().uuidString.lowercased(),
                seq: outerSeq,
                ts: nowMs,
                nonce: nil,
                data: enc
            )
            wireData = (try? encoder.encode(outer)) ?? innerData
        } else {
            wireData = innerData
        }

        if let str = String(data: wireData, encoding: .utf8) {
            task.send(.string(str)) { _ in }
        }
    }

    private func startHeartbeat(ws: URLSessionWebSocketTask) {
        heartbeatTimer?.invalidate()
        heartbeatTimer = Timer.scheduledTimer(withTimeInterval: ProtocolConstants.heartbeatIntervalSeconds, repeats: true) { [weak self, weak ws] _ in
            guard let self, let ws else { return }
            let nowMs = Int64(Date().timeIntervalSince1970 * 1000)
            self.sendEnvelope(type: "ping", id: UUID().uuidString.lowercased(), data: PingPayload(sentAt: nowMs), task: ws)
        }
    }

    private func handleDisconnect() {
        heartbeatTimer?.invalidate()
        sessionKey = nil
        encryptionEnabled = false
        guard !manualDisconnect, let host = targetHost else {
            onStatusChange?(.disconnected)
            return
        }
        reconnectAttempt += 1
        let delay = ReconnectPolicy.delaySeconds(forAttempt: reconnectAttempt)
        onStatusChange?(.reconnecting(attempt: reconnectAttempt))

        let item = DispatchWorkItem { [weak self] in
            self?.openSocket(host: host, port: self?.targetPort ?? ProtocolConstants.defaultPort)
        }
        reconnectWorkItem = item
        DispatchQueue.main.asyncAfter(deadline: .now() + delay, execute: item)
    }

    private func startNetworkMonitor() {
        pathMonitor.pathUpdateHandler = { [weak self] path in
            guard let self else { return }
            if path.status == .satisfied, !self.manualDisconnect, let paired = self.pairedDevice, self.sessionKey == nil {
                DispatchQueue.main.async {
                    self.openSocket(host: paired.host, port: paired.port)
                }
            }
        }
        pathMonitor.start(queue: monitorQueue)
    }
}
