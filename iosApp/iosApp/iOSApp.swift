import SwiftUI
import Shared

@main
struct iOSApp: App {
    // Avoid heavy work in App init to prevent a blank screen; DI is initialized on first view appearance
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}