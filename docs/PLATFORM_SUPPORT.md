# Supported platforms

Wingmate shares domain, data, import/export, and application logic through
Kotlin Multiplatform, while its user interfaces are implemented by multiple
clients. This matrix defines the platform scope used by feature issues and
acceptance testing.

| Client | UI stack | Typing and settings | Screens and OBF/OBZ | Feature-parity expectation |
| --- | --- | --- | --- | --- |
| Android | Jetpack Compose | Supported | Supported | Required for shared features and native Android UX |
| iOS | SwiftUI with the shared Kotlin bridge | Supported | Supported | Required for shared features; native UI and accessibility work must be included |

Wingmate is rebuilding a cross-platform desktop client (`desktopApp/`, Rust +
`iced`, Windows + macOS + Linux) on the shared Kotlin core through a
Kotlin/Native C API; see #268 and ADR-0019. It is in development and is not
yet a supported client.

## Feature acceptance policy

- Domain models, persistence, import/export, and application rules must live in
  shared Kotlin where platform APIs do not require otherwise.
- Native clients call shared Kotlin application rules through their platform
  integration; they must not duplicate those rules in native UI code.
- Experiments may start on one client. A released shared feature must work in
  Android and iOS or document a deliberate platform exception.
- Screen, symbol, layout, and customization issues are complete only when their
  released shared behavior works in Android and iOS SwiftUI.
- Compose UI behavior is verified on Android until a new desktop client exists.
- iOS features must expose the required data and operations through the shared
  bridge and provide equivalent SwiftUI and VoiceOver behavior.
- Hardware-specific features may remain native when they use a platform adapter
  and do not complicate the shared communication model.
- Platform-specific limitations must be recorded in the implementing issue,
  capability document, and release notes; they must not be silently treated as
  feature parity.

This document describes product support scope, not a promise that every existing
feature has already reached full parity.
