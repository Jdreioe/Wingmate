# Wingmate for Linux

Wingmate's native Linux interface is written in Rust with [Iced](https://iced.rs/). It uses the same Kotlin Multiplatform domain and persistence code as the Android app through a small local HTTP bridge. The external FTDI/EVE partner display remains a native Rust driver and never passes through the JVM.

The application supports both Wayland and X11 and follows the user's light/dark preference when that preference is available from Wingmate settings.

## Features

- free-form text composition, predictions, and speech playback controls
- saved phrase editing, category filtering, speech history, and "hold that thought"
- local-system and Azure voice selection with speech-rate control
- pronunciation dictionary
- first-run onboarding with Keyboard/Screens startup selection and analytics consent
- persistent Screen library with blank/calculator templates, run/edit modes, linked-page navigation, cell editing, duplication, locking, and deletion
- native OBF/OBZ import and OBZ export dialogs
- configurable labels/symbols, grid size, UI scaling, high contrast, hold/dwell access, selection sounds, and auditory fishing
- history JSON import/export
- external partner-window mirroring and display settings
- non-blocking communication with the shared Kotlin business-logic service

The older `src/*.qml` files are retained temporarily as migration reference, but they are not loaded or packaged by the Rust executable.

## Prerequisites

- Rust stable
- JDK 21
- Vulkan, OpenGL, or another graphics backend supported by `wgpu`

No Qt, KDE Frameworks, CMake, or QML runtime is required.

## Build and run

From the repository root:

```bash
./gradlew :linuxApp:fatJar
cargo build --manifest-path linuxApp/Cargo.toml --release --bin wingmate-kde
./linuxApp/target/release/wingmate-kde
```

For a machine connected to the FTDI/EVE partner display, install the `libftdi1` development package and build with:

```bash
cargo build --manifest-path linuxApp/Cargo.toml --release --bin wingmate-kde --features partner-window
```

When launched from either the repository root or `linuxApp/`, the executable discovers `linuxApp/build/libs/linuxApp-all.jar` automatically. Packagers can set `WINGMATE_LINUXAPP_JAR` to an absolute installed path. To use an already-running bridge, set `WINGMATE_API_URL`.

## Architecture

- `src/main.rs` contains the complete Iced application, onboarding, Keyboard and Screens workspaces, native state/update/view loop, file dialogs, and typed HTTP client.
- `src/partner_window_bridge.rs` owns the non-blocking UI-to-driver command channel.
- `src/partner_window.rs` owns FTDI/EVE rendering and hardware access.
- `src/main/kotlin/.../KotlinBridge.kt` exposes the existing shared repositories and speech services on localhost.

Check the native layer with:

```bash
cargo check --manifest-path linuxApp/Cargo.toml --bin wingmate-kde
```
