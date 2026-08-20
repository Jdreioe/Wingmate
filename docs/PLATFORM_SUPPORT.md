# Supported platforms

Wingmate shares domain, data, import/export, and application logic through
Kotlin Multiplatform, while its user interfaces are implemented by multiple
clients. This matrix defines the platform scope used by feature issues and
acceptance testing.

| Client | UI stack | Typing and settings | Screens and OBF/OBZ | Feature-parity expectation |
| --- | --- | --- | --- | --- |
| Android | Jetpack Compose | Supported | Supported | Required for shared features and native Android UX |
| iOS | SwiftUI with the shared Kotlin bridge | Supported | Supported | Required for shared features; native UI and accessibility work must be included |
| Linux standalone | Rust (Iced) with the shared Kotlin HTTP bridge | Supported | Supported | Required for shared features and native Linux UX |

## Feature acceptance policy

- Domain models, persistence, import/export, and application rules must live in
  shared Kotlin where platform APIs do not require otherwise.
- Native clients call shared Kotlin application rules through their platform
  bridge; they must not duplicate those rules in Swift or Rust.
- Experiments may start on one client. A released shared feature must work in
  Android, iOS, and Linux or document a deliberate platform exception.
- Screen, symbol, layout, and customization issues are complete only when their
  released shared behavior works in Android, iOS SwiftUI, and Linux Iced.
- Compose UI behavior is verified on Android; there is no shared or desktop
  Compose client.
- iOS features must expose the required data and operations through the shared
  bridge and provide equivalent SwiftUI and VoiceOver behavior.
- The standalone Linux Rust (Iced) client must continue to build and receive a
  native attribution for shared features, including board features.
- Hardware-specific features may remain native when they use a platform adapter
  and do not complicate the shared communication model.
- Platform-specific limitations must be recorded in the implementing issue,
  capability document, and release notes; they must not be silently treated as
  feature parity.

This document describes product support scope, not a promise that every existing
feature has already reached full parity.
