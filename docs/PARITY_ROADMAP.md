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
| Linux standalone | Qt/QML + Rust + Jetty/Ktor bridge | Native Qt app | Native Qt/QML, **full** parity incl. boards/OBF |
| Desktop (Compose JVM) | Compose Multiplatform | **To be retired** | Replaced by Linux Qt on Linux, Mac base= iOS app |
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
  backup/restore, symbol search, and editing access — **keeping Qt/QML**.
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

### Phase 4 — Linux board/OBF parity (keep Qt)
- Add board workspace + OBF/OBZ import/export + backup + symbol search + editing
  access to the Qt/QML client using the **Kotlin bridge** (`linuxApp/.../KotlinBridge.kt`)
  rather than reimplementing.
- Add QML pages/views mirroring the Android/iOS board workspace semantics.

### Phase 5 — Build/release parity
- Single canonical shared framework target consumed by Xcode; unify CI
  (`.github/workflows/deploy-play.yml`, `ci_scripts/ci_post_clone.sh`).
- One artifact set per platform with matching versioning (`version.properties`).

## 4. Feature gap matrix

`[x]` = present, `[~]` = partial/planned, `[ ]` = missing. Cell is the client.

| Feature | Shared logic (Kotlin) | Android (Compose) | iOS (SwiftUI) | Linux (Qt) |
| --- | --- | --- | --- | --- |
| Phrase grid (`PhraseScreen`) | x | x | x | x |
| Categories CRUD / reorder | x | x | x | x |
| Board workspace | x | x | x (SymbolBoardWorkspaceView) | [ ] → planned |
| OBF/OBZ import | x | x | x (Files) | [ ] planned |
| OBF/OBZ export | x (`exportBoardSetAsObz`) | x | [ ] verify | [ ] planned |
| Symbol search (OpenSymbols) | x | x | [ ] duplicate Swift | [ ] planned |
| Custom symbol / photo import | x | x | x (PhotosUI) | [ ] planned |
| Rate/modify `Symbol source: openSymbols` sheets | x | x | x | [ ] |
| Backup/restore | x | x | x | [ ] planned |
| Editing access code | x | x | x (LocalAuth) | [ ] planned |
| Word prediction | x | x | x | x |
| Pronunciation dictionary | x | x | x | x |
| F0 setup | x | x | x (F0SetupView) | [ ] planned |
| System/Azure TTS | x | x | x | x |
| Voice selection | x | x | x | [ ] planned |
| Settings | x | x | x | x |
| Secondary / full-screen display | x (Android) | x | [ ] planned | partner window (Qt) |
| Custom keyboards / board set templates | x | x | x | [ ] planned |

### Note on `[ ]` cells
`[ ]` in the Linux column does **not** mean the feature is absent from logic — the
logic is shared; the Qt UI is missing. Each Phase-4 item is an attribution task.

## 5. Acceptance criteria

A feature/go to **parity** when:
1. Shared behavior passes in commonTest (KMP).
2. The behavior works in Android (Compose), iOS (SwiftUI), and Linux (Qt) — or a
   documented native-only exception with release-note coverage.
3. No Swift/QML/Qt re-implementation of shared logic without a bridge call-through.
4. Build/release produces all platform artifacts from one command set.

## 6. Out of scope for now
- Pre-populated vocabularies, voice banking, remote editing, session sync, battery
  indicator, alternative scanning axes — see `FEATURES.md` `[ ]` items; tracked
  separately, not part of this parity effort unless present in shared logic already.