iOS integration notes

The Kotlin Multiplatform module exposes `overrideIosSpeechService()` which will register the iOS `ConfigRepository` implementation into Koin.

Call from Swift (AppDelegate.swift) after the Kotlin framework is initialized and after calling `initKoin(...)` if applicable:

```swift
import shared

// after Kotlin framework init
KotlinSharedKt.initKoinNil() // pseudo-call if you use an init wrapper; adjust to your generated API
SharedKt.overrideIosSpeechService()
```

Details:
- The actual generated Swift names depend on your Kotlin package and the way you expose functions. Look in the generated framework header (Kotlin/Native) to find the exact symbol names.
- `IosConfigRepository` stores Azure Speech configuration in a this-device-only Keychain item.
  A legacy `NSUserDefaults` value is deleted only after a verified Keychain write.

## Linux Azure credentials

The Linux client stores Azure Speech configuration in Secret Service/libsecret
(`secret-tool`) or KWallet (`kwallet-query`). There is no plaintext file fallback:
when neither secure backend is installed or unlocked, saving fails and the UI must
ask the user to make a keyring available. Legacy `~/.config/wingmate/config.json`
is deleted only after a verified secure write.
- If your app uses a Flutter host, call the function from the iOS runner using the generated Kotlin/Native bridge.
