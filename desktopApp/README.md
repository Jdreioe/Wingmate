# Wingmate desktop

The desktop client is a pure [`iced`](https://iced.rs/) shell around the shared
Kotlin core. Cargo builds the Kotlin/Native static library first and links it
into the final executable, so users need neither a JVM nor a sidecar process.

## Install on Linux

From a checkout, run as your normal desktop user:

```sh
bash scripts/install-wingmate.sh --setup-gaze
```

This downloads the latest stable GitHub release, checks its SHA-256 against the
release's `SHA256SUMS`, installs it under
`${XDG_DATA_HOME:-~/.local/share}/wingmate-app`, and adds an application-menu
launcher. It verifies download integrity over HTTPS; it does not verify the
release's GPG signature. Omit `--setup-gaze` if you do not use a Tobii tracker.
Only USB rule installation and reload use `sudo`.

Until a release is published, install a locally built, trusted AppImage:

```sh
bash scripts/install-wingmate.sh --appimage /path/to/Wingmate.AppImage --setup-gaze
```

Use `--version vX.Y.Z` to select a release, including a prerelease. Local files
are copied without checksum verification. Re-running updates the installed
AppImage; close Wingmate first. Application data is kept separately and preserved.
The download currently supports x86_64 Linux only. Install `curl`, `python3`,
and your distribution's AppImage/FUSE runtime support if needed, plus
`speech-dispatcher` for speech.

USB setup grants the active local desktop user access to the TD-I13 (`2104:031e`)
and Eye Tracker 5 (`2104:0313`). Reconnect the tracker after setup. Firmware and
bootloader access are excluded. Linux AppImages bundle a pinned `tobiifreed`; opt into starting it under
**Settings > Access**. Calibration remains an external prerequisite. See
[the gaze setup](../docs/HEAD_EYE_TRACKING.md#native-gaze-on-the-td-i13-linux-development-build).

To uninstall, remove the `wingmate-app` directory and
`applications/io.github.jdreioe.wingmate.desktop` under your data directory.
Optionally remove `/etc/udev/rules.d/70-wingmate-tobii.rules` with administrator
permission, reload udev rules, and reconnect the tracker. Keep
`~/.local/share/wingmate` to preserve your communication data.

## Run locally

Install the platform speech service (`speech-dispatcher` on Linux; macOS and
Windows provide system speech), then run:

```sh
cd desktopApp
cargo run --release
```

App data is stored in `~/.local/share/wingmate` on Linux,
`~/Library/Application Support/Wingmate` on macOS, and `%APPDATA%/Wingmate` on
Windows. The UI can import OBF/OBZ files, reopen recent files, navigate linked
Pages, compose and speak a message, edit desktop settings and the pronunciation
dictionary, and create or restore a backup.

## Architecture

- `bindings/` compiles the shared Kotlin graph plus a stable C ABI into
  `libwingmate_core.a`.
- `rust/src/bridge/` is the only unsafe Rust module. It owns the Kotlin handle,
  copies returned strings, and exposes typed results.
- `rust/src/screens/`, `settings/`, `message_bar.rs`, and `speech/` contain only
  native presentation and operating-system adapters.

Run `cargo test` from this directory, and `./gradlew
:desktopApp:bindings:allTests` from the repository root.

## Package locally

Install `cargo-packager` 0.11.8, build a release binary, then create the native
package on its target operating system:

```sh
cargo install cargo-packager --version 0.11.8 --locked
# Linux: requires Zig 0.15.2, pkg-config and libusb development headers
bash scripts/build-gaze-daemon.sh
cd desktopApp
cargo build --release --locked
cargo packager --release --formats appimage  # Linux
```

Use `dmg` on macOS and `wix` on Windows. Normal desktop CI builds unsigned
installers on all three operating systems. A `v*.*.*` tag starts the release
workflow, which refuses to publish unless macOS and Windows signing credentials
and the Linux release-signing key are configured. The resulting GitHub release
contains an `.AppImage`, notarized `.dmg`, signed `.msi`, `SHA256SUMS`, and an
armored signature for the checksum file. It also includes the public release key
needed to verify that signature.

The release tag must match the shared version in `version.properties`; the
workflow refuses to publish a differently versioned installer and binary.

## Screen editor

Choose **New Screen** in **Settings > Screens**, or **Edit Screen** in an open
Screen. That settings section also holds the OBF/OBZ importer and the recent
files list; the Library screen only opens a saved Screen.

The editor keeps a Kotlin draft until **Save Screen**. Save Screen is only
available once every form change has been applied to the draft; until then the
header names the buttons still to press. Apply form changes to
that draft with the adjacent buttons; **Discard / close** asks before dropping
unsaved work. Select a Cell to create or edit a Button, including hidden Buttons.
Row/column controls move or swap Buttons, and span controls resize them using the
shared Grid rules. Pages can be added, renamed, resized and made the starting Page;
Buttons can link to any Page in the Screen.

Locked and system Screens cannot be edited here. Existing symbols, recordings,
actions and extension metadata survive edits; rich Page elements are listed as
unsupported placeholders. Symbol/action authoring, rich Page-element editing,
Editing access credential setup, Page deletion and OBZ export are not yet exposed.

### Preparing for alternative input

Editor controls use large hit areas (at least 48 logical pixels high), with
120 × 88 Cell targets that do not shrink when a Grid grows. Save/Discard and
explicit scroll controls stay outside the scrolling content. Moving and resizing
use labeled controls, without requiring drag gestures; selection and hidden state
have text cues. Discard confirmation replaces the editor controls while open.

`editor::controls::Action`, `editor::Event` and `editor::Field` identify semantic
actions, Cell anchors and form fields independently of pointer events. Future gaze
dwell and switch scanning should dispatch these same events, including scrolling,
Page choice and confirmation, instead of adding separate mutation paths. Dwell,
scan traversal, focus highlighting and assistive text entry are not implemented
by this editor increment; Tab/Shift+Tab currently navigates native focusable inputs.
