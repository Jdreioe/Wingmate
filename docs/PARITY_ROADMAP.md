# Wingmate Native-Parity Roadmap

Status: **Proposed** (not yet implemented)

Goal: Create feature, logic, build, and data parity across the native UI
clients while **removing the shared Compose-Multiplatform UI** and keeping a
native UI on every platform.

## 1. Target architecture

Wingmate keeps **one KMP logic module** and **independent native UIs**.

| Target | UI toolkit | Status today | Goal |
| --- | --- | --- | --- |
| Android | Jetpack Compose (Android-native) | Migrating off shared Compose | Standalone native Compose |
| iOS / iPadOS | SwiftUI | Uses shared `KoinBridge` | Native SwiftUI, full parity |
| Linux standalone | Rust (Iced) + Ktor bridge | Native Rust (Iced) app | Native Iced, **full** parity incl. boards/OBF |
| Desktop (Compose JVM) | Compose Multiplatform | **To be retired** | Replaced by Linux Iced on Linux, Mac base= iOS app |
| Windows | — (future) | Not built yet | Native Windows app (Qt/reuse of Linux shareable) |
| macOS | — | — | Build from iOSApp codebase |

Rules:
- **No `composeApp` shared UI.** Compose exists only inside Android, using its own
  module, never shared to other platforms through `commonMain`.
- All domain, data, import/export, backup, prediction, speech, settings, and board
  rules live in **shared Kotlin** (`shared`, `core/*`, `feature/*`) and are exposed to
  native UIs via the Koin bridge and small per-platform adapters.
- Platform UIs stay native. Cross-platform builds do **not** reuse UI code.

## 2. Platform scoping update

`docs/PLATFORM_SUPPORT.md` currently defers board support on Linux and treats
Compose as a required client. This roadmap overrides that for the new direction:

- Linux standalone must reach **feature parity** for boards, OBF/OBZ import/export,
  backup/restore, symbol search, and editing access — **keeping the native Rust (Iced) client**.
- The Compose `desktopApp` and the shared `composeApp` become deprecated targets to
  be dissolved once each feature exists natively elsewhere.

## 3. Phase plan (iOS first)

### Phase 0 — Audit baseline (this doc)
- Confirm client inventory, bridge surface, and current UI frameworks.

### Phase 1 — iOS shared-logic parity
- Remove Swift-side re-implementations that duplicate shared Kotlin.
  - Confirmed example: the OpenSymbols HTTP client is duplicated in
    `iosApp/iosApp/Views/SymbolBoardWorkspaceView.swift` and
    `iosApp/iosApp/Sheets/Sheets.swift`, while shared Kotlin
    (`core/data/...OpenSymbolsClient.kt`) already implements token + search.
    - Add `KoinBridge` functions to call shared `OpenSymbolsClient`.
    - Update the two Swift views to use the bridge; delete the Swift duplicate.
- Audit `iosApp/iosApp/Support/*` for other duplicated logic to route through the
  bridge (speech config, prediction learning, pronunciation dict CRUD).
- Regen/verify the shared framework target consumed by the Xcode project.

### Phase 2 — iOS feature gaps
- Walk `composeApp/.../ui/*` screens vs. `iosApp/iosApp/Views/*` and wire any
  genuinely missing shared capability into SwiftUI:
  - Candidate: verify OBF/OBZ **export** of a board set (bridge already has
    `exportBoardSetAsObz`), pronunciation dictionary (bridge has CRUD), editing
    access, F0, prediction training, secondary/full-screen display parity.
- Ensure accessibility (VoiceOver) labels match Android/Compose where behaviors differ.

### Phase 3 — Android decoupling (goes native-only Compose)
- Dissolve `composeApp` shared UI; move Compose UI into `androidApp` as the sole
  Android client. Keep Android on Jetpack Compose.
- Remove `desktopApp` Compose wiring; stop publishing Compose artifacts for other
  targets.
- Ensure `androidApp` uses the same shared KMP logic module (unchanged).

### Phase 4 — Linux board/OBF parity (native Iced client)
- Add board workspace + OBF/OBZ import/export + backup + symbol search + editing
  access to the Iced client using the **Kotlin bridge** (`linuxApp/.../KotlinBridge.kt`)
  rather than reimplementing.
- Add Iced views mirroring the Android/iOS board workspace semantics.
- **Status: completed** for the workspace/library/import-export/backup surface
  (see git log for issues #135/#137). Remaining UX gaps are tracked in
  `docs/LINUX_UX_AUDIT.md` (P0: setting-real behavior + symbol image rendering,
  P1: scanning, prediction training, editor expansion, P2: polish).

### Phase 5 — Build/release parity
- Single canonical shared framework target consumed by Xcode; unify CI
  (`.github/workflows/deploy-play.yml`, `ci_scripts/ci_post_clone.sh`).
- One artifact set per platform with matching versioning (`version.properties`).

## 4. Feature gap matrix

`[x]` = present, `[~]` = partial/planned, `[ ]` = missing. Cell is the client.

| Feature | Shared logic (Kotlin) | Android (Compose) | iOS (SwiftUI) | Linux (Iced) |
| --- | --- | --- | --- | --- |
| Phrase grid (`PhraseScreen`) | x | x | x | x |
| Categories CRUD / reorder | x | x | x | x |
| Board workspace | x | x | x (SymbolBoardWorkspaceView) | x (run/edit/grid) |
| OBF/OBZ import | x | x | x (Files) | x |
| OBF/OBZ export | x (`exportBoardSetAsObz`) | x | x | x |
| Symbol search (OpenSymbols) | x | x | x (shared client now) | x (locale-aware rendering + cache) |
| Custom symbol / photo import | x | x | x (PhotosUI) | x |
| Symbol - source rate/modify sheets | x | x | x | [ ] |
| Backup/restore | x | x | x | x |
| Editing access (lock / code) | x | x | x (LocalAuth) | x (Secret Service/KWallet) |
| Word prediction | x | x | x | x |
| Pronunciation dictionary | x | x | x | x |
| F0 setup | x | x | x (F0SetupView) | x (partner window driver) |
| System/Azure TTS | x | x | x | x (Az-Added) |
| Voice selection | x | x | x | x |
| Settings | x | x | x | x |
| Full-screen display | x | x | x | x |
| Hardware secondary display | platform adapter | x | not exposed (documented exception) | partner window (Rust) |
| Custom keyboards / board set templates | x | x | x | x (blank + calculator) |

### Note on `[ ]` cells
`[ ]` in the Linux column does **not** mean the feature is absent from logic — the
logic is shared; the native Iced UI is missing. Each Phase-4 item is an attribution task.

## 5. Acceptance criteria

A feature/go to **parity** when:
1. Shared behavior passes in commonTest (KMP).
2. The behavior works in Android (Compose), iOS (SwiftUI), and Linux (Iced) — or a
   documented native-only exception with release-note coverage.
3. No Swift/QML/Qt-style re-implementation of shared logic without a bridge call-through.
4. Build/release produces all platform artifacts from one command set.

## 6. Out of scope for now
- Pre-populated vocabularies, voice banking, remote editing, session sync, battery
  indicator, alternative scanning axes — see `FEATURES.md` `[ ]` items; tracked
  separately, not part of this parity effort unless present in shared logic already.
