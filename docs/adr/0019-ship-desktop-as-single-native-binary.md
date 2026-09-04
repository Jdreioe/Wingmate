# Ship the desktop client as a single native binary on Kotlin/Native

The desktop client (`desktopApp/`) embeds the shared Kotlin core
(`core/domain`, `core/data`, `feature/communication/domain`) as a
Kotlin/Native static library (`libwingmate_core.a`). Kotlin/Native's generated
C API exposes a `wm_*` function set that exchanges JSON strings, and
`rust/src/bridge/` — the only unsafe Rust module — wraps it as typed results
for the Rust `iced` shell. Desktop-specific wiring supplies per-OS adapters
for system TTS, file storage, and the OS data directory. No JVM is required at
install or runtime.

The removed `linuxApp/` ran a JVM sidecar (`fatJar`) with a localhost HTTP
bridge (`KotlinBridge.kt`). That was easier to build but shipped two
processes plus a Java runtime, with port/auth and version-skew seams.
Distribution seamlessness (one `.exe` / `.dmg` / binary, unsigned bare
binaries + checksums in v1) is the point of a Rust desktop, so we accept
the binding-maintenance cost instead.

Consequences:

- Domain rules stay in Kotlin. Rust never re-implements parsing, backup,
  or composition logic; new bridge surface must be exposed through the C API
  and wrapped by the safe Rust FFI layer first.
- `shared/` is not built for the desktop native targets, so `DesktopCore` is
  its own application boundary instead of a caller of `BoardsFacade`,
  `SettingsFacade`, `SpeechFacade`, and `CommunicationFacade`. Screen
  activation and message-bar state are consequently implemented twice, and the
  desktop store persists a `CommunicationSessionSnapshot` that the desktop
  shell does not read. Converging desktop onto the shared facades is follow-up
  work and a prerequisite for listing desktop as a supported client.
- OBZ/OBF parsing, symbol search, and image resolution stay in Kotlin; the
  bridge returns resolved file paths or data URLs and Rust only decodes and
  renders them.
- v1 speech is system TTS only on all three OSes, invoked as a subprocess
  (`spd-say`, `say`, PowerShell `System.Speech`). Because system TTS accepts no
  SSML, the pronunciation dictionary's `text` aliases are substituted in Kotlin
  before Rust speaks, and the phonetic alphabets stay with the cloud engines.
  BYOK cloud voices are out of scope for desktop v1, so no secure credential
  storage ships with it.
- Desktop reuses the shared Kotlin repository contracts and the version-1
  backup archive format, over an atomic JSON state file in the OS-standard
  data directory. Backup portability, rather than reuse of Android or iOS
  storage code, is the compatibility contract.
- While desktop is being rebuilt, it consumes behavior already released on
  Android/iOS and is not a shared-feature release gate. Once desktop becomes a
  supported client, applicable shared features must include it or document a
  deliberate platform exception.
