# Linux (Iced) UX Gap Audit & Implementation Plan

Status: **Parity pass implemented — remaining polish tracked below**
Branch context: `feature/platform-parity` (post Phase-3/4 shared-logic consolidation)

Scope: the native Rust (COSMIC/Iced) client in `linuxApp/src/main.rs` and its
local Kotlin bridge (`linuxApp/.../KotlinBridge.kt`). The board/OBF/backup work
introduced in recent commits already closed the Phase-4 items; this document
audits what **persists or saves but is not usable**, and plans the missing
feature/UX work on top of the shared Kotlin logic.

## 1. What works today (verified in `main.rs`)

- Communicate workspace: draft + TTS speak/pause/stop, prediction bar, markup
  buttons (pause/emphasis/secondary-language), categories with filtering,
  phrase CRUD + per-phrase "say something different", speech history view +
  clear + import/export, "hold that thought", fullscreen draft.
- Settings: Speech (voice, engine, rate, primary/secondary language, Azure),
  Display, Access, Startup, Privacy (analytics, logging, backup/restore),
  Partner window.
- Screens library: create (blank/calculator), run/Edit, duplicate, lock,
  OBF/OBZ import + OBZ export, delete; board workspace (page tabs, grid, cell
  editor with OpenSymbols search, cell label/vocalization, navigation links).

## 2. Settings behavior

Display scaling (`fontSizeScale`, `buttonScale`, and `inputFieldScale`) is exposed
in the native Display settings and applied to the communication workspace, phrase
grid, board grid, screen library, message input, and fullscreen message. Hold,
dwell, selection highlighting, debounce, selection sound, auditory exploration,
and single-switch scanning are implemented through one Linux activation path.

## 3. Missing features / UX (vs. Android + iOS parity)

### Visual
1. **Symbol/image rendering (implemented)** — phrase and board images render from
   cached raster or SVG data; remote images are cached and local images are copied
   into Wingmate application data.
2. **Local image import (implemented)** — board fields and saved phrases accept
   personal images through the native file chooser.

### Board Run behavior
3. **Board behavior editing (implemented)** — page activation and return behavior
   can be edited and return navigation executes in Run mode.
5. **Hidden buttons / babble (implemented)** — hidden fields remain visible in Edit
   and are omitted from Run.
6. **Cell editor expansion (implemented)** — background colour, page links, hidden
   state, OBF actions, local images, and locale-aware OpenSymbols search are exposed.
7. **Board/page management (implemented)** — rename, resize, delete, and set-home
   controls are available for existing pages.

### Predictions & voice plumbing
8. **Prediction training (implemented)** — the bridge trains at bootstrap and after
   restore/import, and learns completed spoken phrases.
9. **Voice preview (implemented)** — the locale-filtered voice picker stages a
   candidate that can be auditioned without changing settings or speech history,
   then applied explicitly.
10. **Playback/status notifications (implemented)** — bridge speech state and
    errors render in a persistent, high-contrast status banner on every view.

### Workflows & polish
11. **Phrase/category parity (implemented)** — Linux preserves folder/link, hidden,
    image, category, and recording metadata; supports management visibility,
    reorder, category rename, and recorded-audio playback.
12. **Keyboard shortcuts (implemented)** — `Ctrl+Enter` speaks the active
    keyboard/board message and `Esc` stops playback globally.
13. **Status visibility (implemented)** — persistent semantic status/error
    banners remain visible in onboarding, workspaces, Settings, and fullscreen.
14. **i18n (implemented for everyday surfaces)** — navigation, onboarding,
    communication, speech/display/access/privacy Settings, access-code UI,
    board essentials, and pronunciation use Fluent resources with English and
    Danish catalogs. Imported/user content remains untranslated by design.
17. **Editing access code (implemented)** — Linux uses the shared controller,
    stores only its salted verifier in Secret Service or KWallet, honors the
    session timeout, and gates vocabulary, category, screen/page, and field edits
    without blocking communication.
15. **Onboarding** — functional but has no OpenBoardSet/screens preview. The
    configured `startupBoardSetId` is honored when launching into Screens mode.
16. **`oscKeyboardScale`/`virtualMicEnabled`** — shared settings exist but are
    not exposed on Linux (accept as Linux-office posture only if documented).

## 4. Prioritized plan

### P0 — make settings real + render boards (highest impact)
1. **Image pipeline**: Rust-side image cache fed by `/api/images/fetch`
   (and `url`-derived keys), applied to board cells as well as phrase cards;
   decode via `image` crate into Iced `Image`.
2. **Apply display settings (partial)**: label/symbol visibility and ordering,
   high contrast, and the board message bar are implemented. Mapping
   `fontSizeScale`/`buttonScale`/`inputFieldScale` to native widget dimensions remains.
3. **Board run composition (implemented)**: the message bar supports
   speak/clear/backspace, and cells use shared Kotlin activation, localization,
   spelling, screen/page override, and sentence rules. Exposing Activation/Return
   behavior editing remains separate follow-up work.
4. **Access timing primitives**: implement `hold/dwell/debounce/highlight`
   around pointer events in Communicate + board grid (dwell needs a small
   subscription timer); play `selectionSound` via the existing `aplay` path
   (Keying small beep asset through the LinuxAudio.service).

### P1 — parity features
5. **Switch scanning on Communicate + board** — reuse existing settings
   (`scan*`); row/col/linear order via bridge scan rules; dwell + auto-advance;
   area toggles honored — mostly new Runnable task.
6. **Training/learning** — call `/api/predict/train` once `LoadedBoardSets`
   after restore; call `/api/predict/learn` after every `Speak` and after cell
   activation; present "training" in the status line.
7. **Board editor expansion** — color picker; local-image picker (RFD dialog
   + new bridge media-import endpoint reusing shared image storage);
   link-to-board picker; hidden flag; page name edit/delete + resize
   (`/api/boardsets/{setId}/boards/{boardId}/size`).
   (edit taps resolve to the anchor).

### P2 — polish
9. Phrase/board DTO parity (`parentId`, `linkedBoardId`, `isHidden`,
   `recording` path) + category rename.
10. Global shortcuts + always-visible status bar, wire i18n strings
   (`fl!()`), voice preview (see #9), `startupBoardSetId` honored on launch,
    gaps to onboarding previews.

## 5. Acceptance criteria per item
- The bridge remains the only place shared Kotlin rules are implemented
  (no Rust re-implementation of session/field/actions logic) — re-use the
  existing `/api/predict/insert` pattern for new rules.
- `cargo check --manifest-path linuxApp/Cargo.toml --bin wingmate` and
  `./gradlew :linuxApp:fatJar` pass.
- Every P0 item has a visible on-screen effect at settings-save time
  (no phantom toggles remain within the P0 list).
