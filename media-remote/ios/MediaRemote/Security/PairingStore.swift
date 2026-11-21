import Foundation
import Security

struct PairedAndroidDevice: Codable, Identifiable, Equatable {
    var id: String { deviceId }
    let deviceId: String
    var deviceName: String
    var host: String
    var port: Int
    var sharedSecretBase64: String
    var lastConnectedAt: Date
}

final class PairingStore {
    private let service = "com.mediaremote.ios.paired"
    private let devicesAccount = "paired_devices_v1"
    private let clientIdAccount = "client_id_v1"

    var clientId: String {
        if let existing = readKeychainString(account: clientIdAccount) {
            return existing
        }
        let created = "ios-\(UUID().uuidString.prefix(12).lowercased())"
        saveKeychainString(created, account: clientIdAccount)
        return created
    }

    func loadDevices() -> [PairedAndroidDevice] {
        guard let raw = readKeychainData(account: devicesAccount) else { return [] }
        return (try? JSONDecoder().decode([PairedAndroidDevice].self, from: raw)) ?? []
    }

    func upsertDevice(_ device: PairedAndroidDevice) {
        var list = loadDevices().filter { $0.deviceId != device.deviceId }
        list.insert(device, at: 0)
        if let data = try? JSONEncoder().encode(list) {
            saveKeychainData(data, account: devicesAccount)
        }
    }

    func removeDevice(deviceId: String) {
        let list = loadDevices().filter { $0.deviceId != deviceId }
        if let data = try? JSONEncoder().encode(list) {
            saveKeychainData(data, account: devicesAccount)
        }
    }

    private func readKeychainString(account: String) -> String? {
        guard let data = readKeychainData(account: account) else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func saveKeychainString(_ value: String, account: String) {
        if let data = value.data(using: .utf8) {
            saveKeychainData(data, account: account)
        }
    }

    private func readKeychainData(account: String) -> Data? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)
        return status == errSecSuccess ? result as? Data : nil
    }

    private func saveKeychainData(_ data: Data, account: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account
        ]
        SecItemDelete(query as CFDictionary)
        var attributes = query
        attributes[kSecValueData as String] = data
        attributes[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        SecItemAdd(attributes as CFDictionary, nil)
    }
}
