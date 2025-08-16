# Wingmate KMP port (Onion + Bloc)

This adds a Kotlin Multiplatform module (`shared`) with Onion layers:
- domain: models + repository contracts
- application: Bloc-style state managers
- infrastructure: in-memory repo implementations for now

Android app (`androidApp`) shows basic Compose UI wiring to `PhraseBloc`.

## Build Android

- Open this `kmp` folder in Android Studio, or run Gradle from CLI.

## iOS integration (no shared UI)

- Create an Xcode iOS app target that depends on the generated `shared` framework.
- Expose simple Swift wrappers to create and use `PhraseBloc`/`SettingsBloc` via Koin.
- Use UIKit/SwiftUI for views; observe `StateFlow` via Kotlin `Flow` <-> Combine bridge or callbacks.

Next steps:
- Replace in-memory repos with real persistence/network.
- Map existing Flutter features into `domain` contracts first, then implement per-platform infra.
