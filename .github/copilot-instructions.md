# Wingmate - AI Agent Instructions

> **Project**: AAC (Augmentative and Alternative Communication) app for KMP (Kotlin Multiplatform)  
> **Package**: `io.github.jdreioe.wingmate`  
> **Stack**: Kotlin 2.2.0, Compose Multiplatform 1.8.2, Koin, MVIKotlin, Ktor

## Architecture Overview

### Module Structure
```
/shared           - Core business logic (KMP: Android, iOS, JVM)
/androidApp       - Android-specific UI and entry point
/desktopApp       - Desktop (JVM) Compose application
/iosApp           - SwiftUI iOS application
/composeApp       - Shared Compose UI components
```

### Layer Architecture (Clean Architecture)
```
shared/src/commonMain/kotlin/io/github/jdreioe/wingmate/
├── domain/        # Interfaces: SpeechService, *Repository, models
├── application/   # Use cases: PhraseBloc, VoiceBloc, SettingsBloc (MVIKotlin)
├── infrastructure/# Implementations: InMemory*, Azure*, platform-specific
└── di.kt          # Koin module configuration
```

## Key Patterns

### 1. Dependency Injection (Koin)
All dependencies use Koin. Platform-specific implementations override base modules:

```kotlin
// Base module in di.kt
single<SpeechService> { NoopSpeechService() }

// Desktop overrides in desktopApp
loadKoinModules(module { single<SpeechService> { DesktopSpeechService() } })

// iOS overrides via IosDi.kt
overrideIosSpeechService()  // Called from Swift
```

### 2. BLoC Pattern (MVIKotlin)
State management uses MVIKotlin stores:
- `PhraseBloc` - Phrase list management
- `VoiceBloc` - Voice selection
- `SettingsBloc` - App settings

### 3. Repository Pattern
All data access goes through interfaces in `domain/repository.kt`:
- `PhraseRepository`, `CategoryRepository` - AAC items
- `ConfigRepository` - Azure credentials (secure storage)
- `SpeechService` - TTS operations

## Security Requirements

### 🔐 CRITICAL: User-Provided Azure Keys
**Architecture**: Users bring their own Azure Speech subscription keys (free tier model).

**Secure Storage Requirements:**
- ✅ Android: Use `EncryptedSharedPreferences` or Android Keystore
- ✅ iOS: Use iOS Keychain Services
- ✅ Desktop: Use OS keyring (libsecret on Linux, Keychain on macOS, Credential Manager on Windows)
- ❌ NEVER store in plain DataStore/SharedPreferences
- ❌ NEVER hardcode developer keys in the app

**Premium Subscription (Future):**
If offering a premium tier where YOU provide Azure keys:
- Use token exchange backend (see `docs/AZURE_TOKEN_EXCHANGE.md`)
- Backend fetches your key from Key Vault, returns short-lived tokens
- `SecureAzureSpeechService` + `TokenExchangeClient` handle token refresh

## Platform Entry Points

| Platform | Entry | DI Setup |
|----------|-------|----------|
| Desktop | `desktopApp/.../Main.kt` | `initKoin()` + `loadKoinModules()` |
| Android | `androidApp/.../MainActivity.kt` | `initKoin()` + Android module |
| iOS | `iosApp/iOSApp.swift` | `startKoinWithOverrides()` from Swift |

## Development Commands

```bash
# Desktop
./gradlew :desktopApp:run

# Android
./gradlew :androidApp:installDebug

# Build shared framework for iOS
./gradlew :shared:linkDebugFrameworkIosArm64
```

## Current Implementation Status

### ✅ Implemented
- Koin DI across all platforms
- MVIKotlin BLoC pattern
- Domain interfaces (`SpeechService`, repositories)
- Platform speech services (Desktop, iOS)
- Azure TTS client via Ktor

### 🔄 In Progress / Needed
- **Secure credential storage** (EncryptedSharedPreferences/Keychain for user Azure keys)
- **Waterfall TTS** (Cache → Azure → System fallback)
- **SQLDelight** for audio cache metadata
- **Phrase audio caching** to reduce API costs
- **Premium subscription** (optional: token exchange backend for managed Azure keys)

## File Naming Conventions

| Type | Pattern | Example |
|------|---------|---------|
| Interface | `domain/*.kt` | `SpeechService` |
| Implementation | `infrastructure/*Impl.kt` or platform prefix | `DesktopSpeechService` |
| iOS impl | `Ios*.kt` | `IosSpeechService.kt` |
| BLoC | `application/*Bloc.kt` | `PhraseBloc.kt` |

## Testing

```bash
./gradlew :shared:test           # Common tests
./gradlew :shared:jvmTest        # JVM-specific tests
```