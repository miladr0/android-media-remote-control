import Foundation
import SwiftUI

@MainActor
final class RemoteViewModel: ObservableObject {
    @Published var connectionStatus: ConnectionStatus = .disconnected
    @Published var mediaState: MediaStatePayload?
    @Published var pairedDevices: [PairedAndroidDevice] = []
    @Published var pendingRequestId: String?
    @Published var lastAckNotice: String?
    @Published var lastErrorBanner: String?

    let browser = BonjourBrowser()
    private let store = PairingStore()
    private lazy var client = RemoteWebSocketClient(clientId: store.clientId)

    init() {
        pairedDevices = store.loadDevices()
        wireClientCallbacks()
        browser.start()
        if let mostRecent = pairedDevices.first {
            client.connect(pairedDevice: mostRecent)
        }
    }

    private func wireClientCallbacks() {
        client.onStatusChange = { [weak self] status in
            self?.connectionStatus = status
        }
        client.onStateReceived = { [weak self] state in
            self?.mediaState = state
            self?.pendingRequestId = nil
        }
        client.onAckReceived = { [weak self] ack in
            if ack.status == "accepted" {
                self?.lastAckNotice = "Accepted: \(ack.action)"
            } else {
                self?.lastAckNotice = nil
                self?.lastErrorBanner = ack.reason ?? "Rejected: \(ack.action)"
            }
        }
        client.onErrorReceived = { [weak self] err in
            self?.lastErrorBanner = "\(err.code): \(err.message)"
            if err.code == "DEVICE_REVOKED", let deviceId = self?.mediaState?.deviceId {
                self?.store.removeDevice(deviceId: deviceId)
                self?.pairedDevices = self?.store.loadDevices() ?? []
            }
        }
        client.onDevicePaired = { [weak self] device in
            self?.store.upsertDevice(device)
            self?.pairedDevices = self?.store.loadDevices() ?? []
        }
    }

    func connect(to device: PairedAndroidDevice) {
        lastErrorBanner = nil
        client.connect(pairedDevice: device)
    }

    func connectManual(host: String, port: Int, device: PairedAndroidDevice? = nil) {
        if let existing = device ?? pairedDevices.first(where: { $0.host == host }) {
            var updated = existing
            updated.host = host
            updated.port = port
            store.upsertDevice(updated)
            pairedDevices = store.loadDevices()
            client.connect(pairedDevice: updated)
        } else {
            lastErrorBanner = "Manual IP requires an already-paired device or QR pairing first."
        }
    }

    func pairWithQrJson(_ jsonText: String) {
        guard let data = jsonText.data(using: .utf8),
              let qr = try? JSONDecoder().decode(PairingQrPayload.self, from: data) else {
            lastErrorBanner = "Invalid MediaRemote QR payload"
            return
        }
        lastErrorBanner = nil
        client.pairAndConnect(qr: qr)
    }

    func forgetDevice(_ device: PairedAndroidDevice) {
        store.removeDevice(deviceId: device.deviceId)
        pairedDevices = store.loadDevices()
        if mediaState?.deviceId == device.deviceId {
            client.disconnect()
            mediaState = nil
        }
    }

    func sendTransportAction(_ action: String) {
        lastErrorBanner = nil
        let reqId = client.sendCommand(CommandPayload(action: action, targetSessionId: nil, volume: nil))
        pendingRequestId = reqId
    }

    func setVolume(_ percent: Int) {
        _ = client.sendCommand(CommandPayload(action: "set_volume", targetSessionId: nil, volume: percent))
    }

    func selectSession(_ sessionId: String) {
        _ = client.sendCommand(CommandPayload(action: "select_session", targetSessionId: sessionId, volume: nil))
    }

    func isActionSupported(_ action: String) -> Bool {
        mediaState?.supportedActions.contains(action) ?? false
    }
}
