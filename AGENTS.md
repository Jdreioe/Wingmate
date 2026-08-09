# Wingmate - Agent Instructions

> **Project**: AAC (Augmentative and Alternative Communication) app for KMP (Kotlin Multiplatform)
> **Package**: `io.github.jdreioe.wingmate`
> **Stack**: Kotlin, Compose Multiplatform (Android only), SwiftUI (iOS), Rust/Iced (Linux), Koin, Ktor

## Architecture Overview

### Module Structure
```
/shared           - Core business logic (KMP: Android, iOS, JVM)
/androidApp       - Android Compose UI (sole Android client)
/iosApp           - SwiftUI iOS application
/linuxApp         - Native Linux client (Rust/Iced + Ktor bridge)
```

### Layer Architecture (Clean Architecture)
```
shared/src/commonMain/kotlin/io/github/jdreioe/wingmate/
├── domain/        # Interfaces: SpeechService, *Repository, models
├── application/   # Use cases: *UseCase, stores, managers
├── infrastructure/# Implementations: InMemory*, Azure*, platform-specific
└── di.kt          # Koin module configuration
```

## Key Principles
- **Share all non-UI logic.** Domain/data/settings/board rules live in shared Kotlin
  (`shared`, `core/*`, `feature/*`) and are exposed to native UIs via `KoinBridge`
  (`shared/src/commonMain/kotlin/io/github/jdreioe/wingmate/KoinBridge.kt`) and the
  Linux HTTP bridge (`linuxApp/.../kde/KotlinBridge.kt`).
- **UI stays native.** SwiftUI (iOS), Compose (Android), Rust/Iced (Linux). No shared
  Compose-Multiplatform UI; Android owns its Compose UI in `androidApp`.
- **No Swift/Rust re-implementation of shared logic** without a bridge call-through.

## Commit Conventions
- **Start the commit message with the issue number**, followed by a concise description, using the format `#{issue number} blablabla` (for example, `#123 Add symbol search`).

## Key Patterns

### Dependency Injection (Koin)
All dependencies use Koin. Platform-specific implementations override base modules:
```kotlin
// Base module in di.kt
single<SpeechService> { NoopSpeechService() }

// iOS overrides via IosDi.kt
overrideIosSpeechService()  // Called from Swift
```

### Repository Pattern
All data access goes through interfaces in `core/domain/.../domain/repository.kt`:
- `PhraseRepository`, `CategoryRepository` - AAC items
- `ConfigRepository` - Azure credentials (secure storage)
- `SpeechService` - TTS operations
- `BoardRepository` / `BoardSetRepository` - OBF boards

## Security Requirements

### CRITICAL: User-Provided Azure Keys
**Architecture**: Users bring their own Azure Speech subscription keys (free tier model).

**Secure Storage Requirements:**
- Android: `EncryptedSharedPreferences` or Android Keystore
- iOS: iOS Keychain Services
- NEVER store in plain DataStore/SharedPreferences
- NEVER hardcode developer keys in the app

## Platform Entry Points

| Platform | Entry | DI Setup |
|----------|-------|----------|
| Android | `androidApp/.../MainActivity.kt` | `initKoin()` + Android module |
| iOS | `iosApp/iOSApp.swift` | `startKoinWithOverrides()` from Swift |
| Linux | `linuxApp/src/main.rs` | Rust/Iced + Ktor Kotlin bridge |

## Build / Test Commands

```bash
# Android
./gradlew :androidApp:assembleDebug

# iOS shared framework (simulator)
./gradlew :shared:linkDebugFrameworkIosSimulatorArm64

# Shared tests (JVM)
./gradlew :shared:jvmTest

# Domain tests (JVM)
./gradlew :core:domain:jvmTest

# Linux standalone
./gradlew :linuxApp:fatJar && cargo build --manifest-path linuxApp/Cargo.toml --bin wingmate

# Linux Rust check
cargo check --manifest-path linuxApp/Cargo.toml --bin wingmate
```

Note: Swift changes cannot be compiled on Linux; they must be verified in Xcode.

## File Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Interface | `domain/*.kt` | `SpeechService` |
| Implementation | `infrastructure/*Impl.kt` or platform prefix | `IosSpeechService.kt` |
| iOS impl | `Ios*.kt` | `IosSpeechService.kt` |
| Swift types | `Ios*` bridge DTOs | `IosBoardCell` |
