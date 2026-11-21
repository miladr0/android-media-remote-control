import Foundation
import Network

struct DiscoveredAndroidDevice: Identifiable, Equatable {
    let id: String
    let name: String
    let host: String
    let port: Int
    let deviceId: String?
}

final class BonjourBrowser: ObservableObject {
    @Published var devices: [DiscoveredAndroidDevice] = []
    @Published var isSearching: Bool = false

    private var browser: NWBrowser?
    private let queue = DispatchQueue(label: "com.mediaremote.bonjour")

    func start() {
        stop()
        let params = NWParameters.tcp
        params.includePeerToPeer = true
        let descriptor = NWBrowser.Descriptor.bonjour(type: ProtocolConstants.bonjourType, domain: "local.")
        let nwBrowser = NWBrowser(for: descriptor, using: params)

        nwBrowser.stateUpdateHandler = { [weak self] state in
            DispatchQueue.main.async {
                self?.isSearching = (state == .ready)
            }
        }

        nwBrowser.browseResultsChangedHandler = { [weak self] results, _ in
            self?.resolveResults(Array(results))
        }

        browser = nwBrowser
        nwBrowser.start(queue: queue)
    }

    func stop() {
        browser?.cancel()
        browser = nil
        DispatchQueue.main.async { self.isSearching = false }
    }

    private func resolveResults(_ results: [NWBrowser.Result]) {
        for result in results {
            guard case let .service(name, _, _, _) = result.endpoint else { continue }
            var deviceId: String?
            if case let .bonjour(txt) = result.metadata {
                deviceId = txt.dictionary["id"]
            }

            let conn = NWConnection(to: result.endpoint, using: .tcp)
            conn.stateUpdateHandler = { [weak self] state in
                if case .ready = state,
                   let remoteEndpoint = conn.currentPath?.remoteEndpoint,
                   case let .hostPort(host, port) = remoteEndpoint {
                    let cleanHost = Self.cleanIpHost("\(host)")
                    let discovered = DiscoveredAndroidDevice(
                        id: deviceId ?? "\(cleanHost):\(port.rawValue)",
                        name: name,
                        host: cleanHost,
                        port: Int(port.rawValue),
                        deviceId: deviceId
                    )
                    DispatchQueue.main.async {
                        guard let self else { return }
                        if let idx = self.devices.firstIndex(where: { $0.id == discovered.id }) {
                            self.devices[idx] = discovered
                        } else {
                            self.devices.append(discovered)
                        }
                    }
                    conn.cancel()
                } else if case .failed = state {
                    conn.cancel()
                }
            }
            conn.start(queue: queue)
        }
    }

    private static func cleanIpHost(_ raw: String) -> String {
        raw.split(separator: "%").first.map(String.init) ?? raw
    }
}
