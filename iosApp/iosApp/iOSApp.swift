import SwiftUI
import CloudKit
import Shared

@main
struct iOSApp: App {
    @State private var lowPowerMode = ProcessInfo.processInfo.isLowPowerModeEnabled
    @Environment(\.scenePhase) private var scenePhase
    @UIApplicationDelegateAdaptor(WingmateAppDelegate.self) private var appDelegate

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

final class WingmateAppDelegate: NSObject, UIApplicationDelegate {
    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Lightweight: only hands the Swift CloudKit and sign-in adapters to the shared module.
        IosDiBridge().registerCloudKitScreenBridge(bridge: CloudKitScreenShareService.shared)
        CloudDriveWebAuthentication.register()
        return true
    }

    func application(
        _ application: UIApplication,
        configurationForConnecting connectingSceneSession: UISceneSession,
        options: UIScene.ConnectionOptions
    ) -> UISceneConfiguration {
        let configuration = UISceneConfiguration(name: nil, sessionRole: connectingSceneSession.role)
        // Only the main app window receives iCloud share links; other roles keep the system defaults.
        if connectingSceneSession.role == .windowApplication {
            configuration.delegateClass = WingmateSceneDelegate.self
        }
        return configuration
    }
}

/// Receives iCloud share links for shared Screens (CKSharingSupported in Info.plist).
final class WingmateSceneDelegate: NSObject, UIWindowSceneDelegate {
    func scene(_ scene: UIScene, willConnectTo session: UISceneSession, options connectionOptions: UIScene.ConnectionOptions) {
        if let metadata = connectionOptions.cloudKitShareMetadata {
            Task { await CloudKitScreenShareService.shared.accept(metadata) }
        }
    }

    func windowScene(_ windowScene: UIWindowScene, userDidAcceptCloudKitShareWith cloudKitShareMetadata: CKShare.Metadata) {
        Task { await CloudKitScreenShareService.shared.accept(cloudKitShareMetadata) }
    }
}
