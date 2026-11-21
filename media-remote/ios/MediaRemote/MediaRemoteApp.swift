import SwiftUI

@main
struct MediaRemoteApp: App {
    @StateObject private var viewModel = RemoteViewModel()

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environmentObject(viewModel)
                .preferredColorScheme(.dark)
        }
    }
}
