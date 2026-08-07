# Head & eye tracking plan

Inspired by [OneTalker#108](https://codeberg.org/OneTalker/OneTalker/issues/108), adapted to Wingmate’s KMP multi-client model (`docs/PLATFORM_SUPPORT.md`).

## Goal

Let users select communication targets (and optionally drive a pointer) with head or eye tracking, without requiring every platform to ship an in-app gaze stack when the OS already provides one.

## Current state

| Capability | Status |
| --- | --- |
| Dwell-to-select + progress ring | Implemented (Compose phrase grid / OBF cells; `Settings.dwellToSelectMillis`) |
| Hold-to-select | Implemented (`Settings.holdToSelectMillis`) |
| Row/column scanning | Implemented (strongest on iOS accessibility UI) |
| Mouse / OS pointer input | Implemented via standard pointer events |
| OS eye / head tracking | Supported path: iOS Eye Tracking / Head Tracking (18+), Switch Control, Voice Control, Windows Eye Control — no in-app calibration |
| In-app head pointer, joystick mode, pause, custom cursor | Not implemented — tracked in [#116](https://github.com/jdreioe/Wingmate/issues/116) |
| Expression / blink select | Not implemented — tracked in [#117](https://github.com/jdreioe/Wingmate/issues/117) |

Related access issues: [#110](https://github.com/jdreioe/Wingmate/issues/110)–[#121](https://github.com/jdreioe/Wingmate/issues/121).

## Design principles

1. **OS-first on mobile.** Prefer system eye/head tracking and switch control; document setup; harden hit targets, focus order, and dwell.
2. **In-app tracking where OS is weak.** Desktop JVM is the primary target for webcam head tracking and external hardware streams.
3. **Shared selection path.** Tracking only produces pointer position and select intents; phrase/OBF activation stays on existing handlers.
4. **Privacy by default.** Camera frames stay on-device; no raw video upload or retention unless a future feature is explicitly opt-in and documented in `PRIVACY_POLICY.md`.
5. **Platform policy.** Shared domain + Compose (Android/Desktop) required for in-app features; iOS may remain OS-documented until a native need is proven; Linux Qt/QML is out of scope unless an issue explicitly adds it.

## Architecture

```text
[Camera | OS pointer | UDP/HID gaze]
              ↓
   AccessInputSource (platform / expect-actual)
              ↓
   Smoothing + Calibration map
              ↓
   AccessController (shared) → PointerPosition | SelectEvent | Pause
              ↓
   UI hit-test (grid / OBF / sentence box) → existing select handlers
```

Keep ML and camera code out of `commonMain`. Cross the bridge only with:

- pointer samples (`x`, `y`, timestamp, source)
- select triggers (`Dwell`, `Switch`, `Blink`, `Expression`, `Click`)
- commands (`Pause`, `Resume`, `Recalibrate`)

### Suggested shared types (domain)

```text
AccessPointerSource: Mouse | OsGaze | HeadCamera | ExternalStream
AccessSelectMethod:  Dwell | SwitchOrKey | Blink | Expression | Click
AccessCommand:       Pause | Resume | Recalibrate
```

Extend `Settings` only with persisted user preferences (mode, sensitivities, pause bindings, calibration profile id). Do not store video.

## Phased delivery

### P0 — Productize existing access (no camera)

Goal: make OS-driven and mouse-driven tracking usable end-to-end.

- Access method settings UX (Touch / Dwell+pointer / Scanning) grouped clearly
- Keyboard or switch activates the **current hovered / focused** target (gap under eye/head “button to select”)
- Pause access: corner control or binding that suppresses dwell and tracking selects
- Optional high-visibility cursor on Desktop where the platform allows
- Docs: how to enable iOS/Windows eye or head tracking with Wingmate

Primary issue: [#116](https://github.com/jdreioe/Wingmate/issues/116) (partial — pause, switch-to-select, cursor).

### P1 — Desktop head-pointer MVP

Goal: laptop webcam → smoothed head pose → screen pointer → existing dwell path.

- Camera permission + lifecycle on Desktop JVM
- Head-as-pointer mapping (absolute) with optional head-as-joystick (relative velocity + dead zone)
- Smoothing (e.g. one-euro or SMA) to reduce jitter
- 5–9 point calibration UI in Compose settings
- Pause / resume and failure states (no face, permission denied)
- Output feeds the same hover + dwell pipeline as mouse

Does **not** require clinical-grade eye gaze. Set user expectations accordingly.

### P2 — Eye / external hardware / expressions

- Optional iris or vendor SDK behind the same `AccessInputSource`
- UDP/HID listener for third-party streams (Tobii, Beam, etc.)
- Blink and configurable expressions as select triggers — [#117](https://github.com/jdreioe/Wingmate/issues/117)

### P3 — Parity polish

- iOS/Android: only add in-app camera tracking if OS paths still leave users blocked
- Auditory cues for dwell start/complete (respect speech policies)
- Update `FEATURES.md` and `docs/OPENAAC_FEATURE_COMPARISON.md`
- Linux Qt only if Desktop path is proven and an issue scopes it

## Settings surface (planned)

| Setting | Notes |
| --- | --- |
| `accessPointerMode` | `Off` / `OsOrMouse` / `HeadCamera` / `External` |
| `accessSelectMethod` | Dwell (existing ms) and/or switch/key / expression |
| Head pointer vs joystick | Sensitivity, dead zone, invert axes |
| Smoothing amount | Discrete or continuous |
| Pause binding / corner | Always reachable without precise aim |
| Calibration profile | Per display / seating; clearable |

Reuse existing: `dwellToSelectMillis`, `holdToSelectMillis`, scanning fields, debounce/highlight when present.

## Implementation touchpoints

| Area | Likely files / modules |
| --- | --- |
| Domain settings | `core/domain/.../model.kt`, `SettingsRepository` |
| Bridge | `shared/.../KoinBridge.kt`, desktop/iOS bridges as needed |
| Dwell UI | `composeApp/.../PhraseGridItem.kt`, `ObfBoardView.kt` |
| Settings UI | `composeApp/.../SettingsScreen.kt`, `UiSettingsDialog.kt` |
| Desktop camera / pose | new desktop-only source set or small module under `composeApp` / `desktopApp` |
| iOS a11y | existing SwiftUI accessibility sort order / scanning |

## Acceptance criteria (epic-level)

- [ ] User can choose an access mode: Touch | Dwell + OS/mouse pointer | Head track (Desktop) | External stream (when implemented)
- [ ] Dwell time, pause, and (for head track) smoothing/sensitivity persist across restarts
- [ ] Desktop head calibration maps usable reach across the main window
- [ ] Target activation matches touch for speak / navigate / compose behavior
- [ ] No continuous raw video leaves the device
- [ ] Android + Desktop Compose verified for shared UI; iOS documents OS path; Linux out of scope unless stated
- [ ] `FEATURES.md` / comparison doc updated when behavior ships

## Risks

- Webcam head track is not clinical eye gaze; accuracy and fatigue vary
- CPU, battery, and camera permissions
- Jitter and false selects without smoothing + pause
- Must not fire dwell during press, edit mode, or menus (existing guards stay)
- Cursor APIs differ widely; custom cursor may be Desktop-only

## Issue map

| Slice | Issue |
| --- | --- |
| Epic / plan (this doc) | [#123](https://github.com/jdreioe/Wingmate/issues/123) |
| Pointer + head control options | [#116](https://github.com/jdreioe/Wingmate/issues/116) |
| Expression select | [#117](https://github.com/jdreioe/Wingmate/issues/117) |
| P0 — switch-to-select + pause + cursor | [#124](https://github.com/jdreioe/Wingmate/issues/124) — child of #116 / epic |
| P1 — Desktop head-pointer MVP + calibration | [#125](https://github.com/jdreioe/Wingmate/issues/125) — child of #116 / epic |
| P2 — external stream / eye SDK | [#126](https://github.com/jdreioe/Wingmate/issues/126) |
| P3 — parity + docs + mobile decision | [#127](https://github.com/jdreioe/Wingmate/issues/127) |
| TD-I13 gaze tracker as input source | [#129](https://github.com/jdreioe/Wingmate/issues/129) |

## Non-goals (initial)

- Replacing OS eye tracking on iOS/Android in v1
- Shipping OpenCV/MediaPipe in shared code for all targets on day one
- Linux Qt reimplementation of calibration UI
- Cloud inference on face or eye images
