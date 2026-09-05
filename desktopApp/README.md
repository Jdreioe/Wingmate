# Wingmate desktop

The desktop client is a pure [`iced`](https://iced.rs/) shell around the shared
Kotlin core. Cargo builds the Kotlin/Native static library first and links it
into the final executable, so users need neither a JVM nor a sidecar process.

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
