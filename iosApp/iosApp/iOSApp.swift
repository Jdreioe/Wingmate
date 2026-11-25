import SwiftUI
import Shared

@main
struct iOSApp: App {
    @State private var lowPowerMode = ProcessInfo.processInfo.isLowPowerModeEnabled
    @Environment(\.scenePhase) private var scenePhase
    init() {
        // Start dependency injection before any view can resolve a facade. Koin only
        // registers definitions here; singletons are still created on first use.
        IosViewModel.startDependencies()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(lowPowerMode ? .dark : nil)
                .background(lowPowerMode ? Color.black : Color(.systemBackground))
                .onReceive(NotificationCenter.default.publisher(for: .NSProcessInfoPowerStateDidChange)) { _ in
                    lowPowerMode = ProcessInfo.processInfo.isLowPowerModeEnabled
                }
                .onChange(of: scenePhase) { _, phase in
                    if phase == .active {
                        lowPowerMode = ProcessInfo.processInfo.isLowPowerModeEnabled
                    }
                }
        }
    }
}
