# Wingmate iOS host app

This folder contains a SwiftUI iOS host for the KMP shared module.

Quick start:

1. Build a placeholder Kotlin framework (one time):
   ./gradlew :shared:generateDummyFramework

2. In Xcode, create a new iOS App project named `WingmateiOS` in this `iosApp` folder. Make sure the bundle id is unique for your team.

3. Close Xcode and run CocoaPods install:
   cd iosApp
   pod install

4. Open the generated `WingmateiOS.xcworkspace` in Xcode and run on a device/simulator.

Swift notes:
- AppDelegate initializes Koin via `initKoin(nil)` and registers iOS overrides (NSUserDefaults config + AVSpeechSynthesizer service).
- `ContentView` presents a glass (blur) aesthetic and drives data via `KoinBridge` suspend wrappers.
