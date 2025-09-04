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

## Desktop Virtual Mic (Linux)

Enable the "Use virtual microphone for calls" toggle in settings. This creates a PulseAudio/PipeWire null sink named `wingmate_vmic` and a microphone source `wingmate_vmic_mic` mapped to its monitor. TTS playback is routed to that sink so apps can pick up the mic.

- Prereqs: `pactl` and at least one of `ffplay` (recommended), `mpg123`, `paplay`, or `aplay` on PATH.
- After toggling on and playing something once, select microphone input `Wingmate Virtual Mic` (or `wingmate_vmic_mic`) in Zoom/Google Meet.
- If you don't see it, verify modules are loaded:
	- `pactl list short sinks | grep wingmate_vmic`
	- `pactl list short sources | grep wingmate_vmic_mic`
	- If missing, try toggling setting off/on or restarting the app.
