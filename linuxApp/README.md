# Wingmate for Linux

Wingmate's native Linux interface is written in Rust with [Iced](https://iced.rs/). It uses the same Kotlin Multiplatform domain and persistence code as the Android app through a small local HTTP bridge. The external FTDI/EVE partner display remains a native Rust driver and never passes through the JVM.

The application supports both Wayland and X11 and follows the user's light/dark preference when that preference is available from Wingmate settings.

## Features

- free-form text composition, predictions, and speech playback controls
- saved phrase editing, category filtering, speech history, and "hold that thought"
- local-system and Azure voice selection with speech-rate control
- pronunciation dictionary
- first-run onboarding with Keyboard/Screens startup selection and a local-data privacy summary
- persistent Screen library with blank/calculator templates, spanning OBF fields, shared OBF button actions, run/edit modes, linked-page navigation, cell editing, duplication, locking, and deletion
- native OBF/OBZ import and OBZ export dialogs
- configurable phrase-grid columns; light/dark mode follows the desktop appearance portal with explicit Light/Dark fallbacks, while accessibility scaling follows COSMIC
- history JSON import/export
- external partner-window mirroring and display settings, shown only while supported FT232H hardware is connected
- non-blocking communication with the shared Kotlin business-logic service
- Android/iOS-style workspace navigation in the native COSMIC header: Keyboard,
  Screens, and a Settings toggle that returns to the previous workspace
- system symbolic icons, screen-reader names, and touch-sized primary controls

Linux currently hides custom theme/scaling, hold/dwell, selection feedback,
auditory fishing, switch scanning, usage logging, and analytics controls because
the native Iced client does not implement those behaviors yet. See
`../docs/LINUX_ACCESSIBILITY_MATRIX.md` for the support matrix.

On desktops that do not publish their active icon theme, set
`WINGMATE_ICON_THEME=<theme-name>` when launching Wingmate. The app otherwise uses
the desktop-published icon theme and portable symbolic-name aliases.

## Prerequisites

- Rust stable
- JDK 21
- Vulkan, OpenGL, or another graphics backend supported by `wgpu`

No Qt, KDE Frameworks, CMake, or QML runtime is required.

## Build and run

From the repository root:

```bash
./gradlew :linuxApp:fatJar
cargo build --manifest-path linuxApp/Cargo.toml --release --bin wingmate
./linuxApp/target/release/wingmate
```

For a machine connected to the FTDI/EVE partner display, install the `libftdi1` development package and build with:

```bash
cargo build --manifest-path linuxApp/Cargo.toml --release --bin wingmate --features partner-window
```

When launched from either the repository root or `linuxApp/`, the executable discovers `linuxApp/build/libs/linuxApp-all.jar` automatically. Packagers can set `WINGMATE_LINUXAPP_JAR` to an absolute installed path. To use an already-running bridge, set `WINGMATE_API_URL`.

## Architecture

- `src/main.rs` contains the complete Iced application, onboarding, Keyboard and Screens workspaces, native state/update/view loop, file dialogs, and typed HTTP client.
- `src/partner_window_bridge.rs` owns the non-blocking UI-to-driver command channel.
- `src/partner_window.rs` owns FTDI/EVE rendering and hardware access.
- `src/main/kotlin/.../KotlinBridge.kt` exposes the existing shared repositories and speech services on localhost.

Check the native layer with:

```bash
cargo check --manifest-path linuxApp/Cargo.toml --bin wingmate
```
