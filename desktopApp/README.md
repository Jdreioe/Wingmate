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
