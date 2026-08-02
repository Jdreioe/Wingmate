# Supported platforms

Wingmate shares domain, data, import/export, and application logic through
Kotlin Multiplatform, while its user interfaces are implemented by multiple
clients. This matrix defines the platform scope used by feature issues and
acceptance testing.

| Client | UI stack | Communication and settings | Board sets and OBF/OBZ | Feature-parity expectation |
| --- | --- | --- | --- | --- |
| Android | Compose Multiplatform | Supported | Supported | Required for shared and Compose features |
| Desktop | Compose Multiplatform on JVM | Supported | Supported | Required for shared and Compose features |
| iOS | SwiftUI with the shared Kotlin bridge | Supported | Supported | Required for shared features; native UI and accessibility work must be included |
| Linux standalone | Qt/QML | Supported for its existing phrase and partner-window workflows | Not currently exposed | Maintain existing behavior; board-feature parity is out of scope until the board workspace is added |

## Feature acceptance policy

- Domain models, persistence, import/export, and application rules must live in
  shared Kotlin where platform APIs do not require otherwise.
- Board, symbol, layout, and customization issues are complete only when their
  shared behavior works in Android, Desktop Compose, and iOS SwiftUI.
- Compose UI behavior must be verified on both Android and Desktop when input,
  windowing, file access, or accessibility behavior differs.
- iOS features must expose the required data and operations through the shared
  bridge and provide equivalent SwiftUI and VoiceOver behavior.
- The standalone Linux Qt/QML client must continue to build and retain its
  existing workflows, but new board features do not require a second QML
  implementation unless an issue explicitly adds that scope.
- Platform-specific limitations must be recorded in the implementing issue and
  release notes; they must not be silently treated as feature parity.

This document describes product support scope, not a promise that every existing
feature has already reached full parity.
