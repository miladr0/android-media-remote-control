import SwiftUI

struct PairingView: View {
    @EnvironmentObject var vm: RemoteViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var manualPayload = ""

    var body: some View {
        NavigationStack {
            VStack(spacing: 16) {
                QrScannerView { scannedText in
                    vm.pairWithQrJson(scannedText)
                    dismiss()
                }
                .frame(height: 300)
                .clipShape(RoundedRectangle(cornerRadius: 16))
                .padding(.horizontal)

                Text("Point camera at the QR code shown inside LocalCast Receiver on your Xiaomi device.")
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal)

                VStack(alignment: .leading, spacing: 8) {
                    Text("Or paste QR JSON payload")
                        .font(.caption.weight(.medium))
                        .foregroundStyle(.secondary)
                    TextField("{\"v\":1,\"deviceId\":...}", text: $manualPayload)
                        .textFieldStyle(.roundedBorder)
                    Button("Pair with Payload") {
                        vm.pairWithQrJson(manualPayload)
                        dismiss()
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(manualPayload.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
                .padding(.horizontal)

                Spacer()
            }
            .padding(.top)
            .navigationTitle("Pair Android Device")
            .toolbar {
                ToolbarItem(placement: .topBarTrailing) {
                    Button("Cancel") { dismiss() }
                }
            }
        }
    }
}
