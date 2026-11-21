import SwiftUI

struct DeviceListView: View {
    @EnvironmentObject var vm: RemoteViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var manualIp = ""
    @State private var manualPort = "8765"

    var body: some View {
        NavigationStack {
            List {
                Section("Discovered on Local Wi-Fi (_mediaremote._tcp)") {
                    if vm.browser.devices.isEmpty {
                        Text("Searching local network...")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(vm.browser.devices) { dev in
                            Button {
                                if let paired = vm.pairedDevices.first(where: { $0.deviceId == dev.deviceId || $0.host == dev.host }) {
                                    var updated = paired
                                    updated.host = dev.host
                                    updated.port = dev.port
                                    vm.connect(to: updated)
                                    dismiss()
                                } else {
                                    vm.lastErrorBanner = "Scan QR on \(dev.name) to pair before connecting."
                                }
                            } label: {
                                VStack(alignment: .leading) {
                                    Text(dev.name).fontWeight(.medium)
                                    Text("\(dev.host):\(dev.port)").font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }

                Section("Remembered Paired Devices") {
                    if vm.pairedDevices.isEmpty {
                        Text("No paired Android devices yet.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(vm.pairedDevices) { device in
                            HStack {
                                Button {
                                    vm.connect(to: device)
                                    dismiss()
                                } label: {
                                    VStack(alignment: .leading) {
                                        Text(device.deviceName).fontWeight(.medium)
                                        Text("\(device.host):\(device.port)").font(.caption).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer()
                                Button("Forget", role: .destructive) {
                                    vm.forgetDevice(device)
                                }
                                .font(.caption)
                            }
                        }
                    }
                }

                Section("Manual IP Fallback") {
                    TextField("Android IP (e.g. 192.168.1.42)", text: $manualIp)
                        .keyboardType(.decimalPad)
                    TextField("Port", text: $manualPort)
                        .keyboardType(.numberPad)
                    Button("Connect to IP") {
                        let port = Int(manualPort) ?? ProtocolConstants.defaultPort
                        vm.connectManual(host: manualIp.trimmingCharacters(in: .whitespaces), port: port)
                        dismiss()
                    }
                    .disabled(manualIp.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            }
            .navigationTitle("Devices")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Done") { dismiss() }
                }
            }
        }
    }
}
