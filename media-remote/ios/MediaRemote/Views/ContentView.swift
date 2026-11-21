import SwiftUI

struct ContentView: View {
    @EnvironmentObject var vm: RemoteViewModel
    @State private var showDevicesSheet = false
    @State private var showPairingSheet = false
    @State private var sliderVolume: Double = 50
    @State private var isDraggingVolume = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 24) {
                connectionHeader

                if let err = vm.lastErrorBanner {
                    Text(err)
                        .font(.footnote)
                        .foregroundStyle(.red)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer(minLength: 4)

                ArtworkImageView(base64: vm.mediaState?.artworkBase64)
                    .frame(width: 240, height: 240)
                    .clipShape(RoundedRectangle(cornerRadius: 20, style: .continuous))

                VStack(spacing: 6) {
                    Text(vm.mediaState?.appName ?? "No Active Player")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(.secondary)

                    Text(vm.mediaState?.title ?? "Ready on Local Wi-Fi")
                        .font(.title3.weight(.semibold))
                        .lineLimit(2)
                        .multilineTextAlignment(.center)

                    Text(vm.mediaState?.artist ?? (vm.mediaState?.deviceName ?? "Pair or select a Xiaomi device"))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                }
                .padding(.horizontal, 24)

                if let sessions = vm.mediaState?.sessions, sessions.count > 1 {
                    sessionPicker(sessions: sessions)
                }

                transportControls

                volumeControl

                Spacer(minLength: 8)
            }
            .padding()
            .navigationTitle("LocalCast")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    Button {
                        showDevicesSheet = true
                    } label: {
                        Image(systemName: "hifispeaker.2")
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Button {
                        showPairingSheet = true
                    } label: {
                        Image(systemName: "qrcode.viewfinder")
                    }
                }
            }
            .sheet(isPresented: $showDevicesSheet) {
                DeviceListView()
            }
            .sheet(isPresented: $showPairingSheet) {
                PairingView()
            }
            .onChange(of: vm.mediaState?.volume.percent) { newVal in
                if !isDraggingVolume, let newVal {
                    sliderVolume = Double(newVal)
                }
            }
        }
    }

    private var connectionHeader: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(statusColor)
                .frame(width: 8, height: 8)
            Text(statusText)
                .font(.caption.weight(.medium))
                .foregroundStyle(.secondary)
            if let ack = vm.lastAckNotice {
                Text("· \(ack)")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
    }

    private var transportControls: some View {
        let isPlaying = vm.mediaState?.playbackState == "PLAYING"
        let playPauseAction = isPlaying ? "pause" : "play"

        return HStack(spacing: 28) {
            Button {
                vm.sendTransportAction("previous")
            } label: {
                Image(systemName: "backward.fill")
                    .font(.title2)
                    .frame(width: 56, height: 56)
            }
            .disabled(!vm.isActionSupported("previous"))

            Button {
                vm.sendTransportAction(playPauseAction)
            } label: {
                Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                    .font(.largeTitle)
                    .frame(width: 80, height: 80)
                    .background(Circle().fill(Color.accentColor))
                    .foregroundStyle(.white)
            }
            .disabled(!vm.isActionSupported(playPauseAction))

            Button {
                vm.sendTransportAction("next")
            } label: {
                Image(systemName: "forward.fill")
                    .font(.title2)
                    .frame(width: 56, height: 56)
            }
            .disabled(!vm.isActionSupported("next"))

            Button {
                vm.sendTransportAction("stop")
            } label: {
                Image(systemName: "stop.fill")
                    .font(.title3)
                    .frame(width: 48, height: 48)
            }
            .disabled(!vm.isActionSupported("stop"))
            .opacity(vm.isActionSupported("stop") ? 1.0 : 0.35)
        }
    }

    private var volumeControl: some View {
        let isFixed = vm.mediaState?.volume.isFixed ?? false
        return VStack(spacing: 4) {
            HStack(spacing: 12) {
                Image(systemName: "speaker.fill")
                    .foregroundStyle(.secondary)
                Slider(
                    value: $sliderVolume,
                    in: 0...100,
                    step: 1,
                    onEditingChanged: { editing in
                        isDraggingVolume = editing
                        if !editing {
                            vm.setVolume(Int(sliderVolume))
                        }
                    }
                )
                .disabled(isFixed || vm.connectionStatus != .connected)
                Image(systemName: "speaker.wave.3.fill")
                    .foregroundStyle(.secondary)
            }
            if isFixed {
                Text("Fixed volume output reported by Android")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.horizontal, 16)
    }

    private func sessionPicker(sessions: [SessionSummary]) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 8) {
                ForEach(sessions) { session in
                    Button {
                        vm.selectSession(session.sessionId)
                    } label: {
                        Text(session.appName)
                            .font(.caption.weight(.medium))
                            .padding(.horizontal, 12)
                            .padding(.vertical, 6)
                            .background(session.isSelected ? Color.accentColor : Color.secondary.opacity(0.2))
                            .foregroundStyle(session.isSelected ? .white : .primary)
                            .clipShape(Capsule())
                    }
                }
            }
            .padding(.horizontal, 16)
        }
    }

    private var statusColor: Color {
        switch vm.connectionStatus {
        case .connected: return .green
        case .connecting, .pairing, .authenticating, .reconnecting: return .orange
        case .disconnected, .error: return .red
        }
    }

    private var statusText: String {
        switch vm.connectionStatus {
        case .disconnected: return "Disconnected"
        case .connecting: return "Connecting..."
        case .pairing: return "Pairing..."
        case .authenticating: return "Authenticating..."
        case .connected: return "Connected to \(vm.mediaState?.deviceName ?? "Android")"
        case .reconnecting(let attempt): return "Reconnecting (attempt \(attempt))..."
        case .error(let msg): return msg
        }
    }
}
