import UIKit
import Shared

@main
class AppDelegate: UIResponder, UIApplicationDelegate {
    var window: UIWindow?

    func application(_ application: UIApplication, didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]?) -> Bool {
    // Initialize Koin and iOS-specific overrides
    SharedKt.initKoin()
    IosDiKt.overrideIosSpeechService()
        return true
    }
}
