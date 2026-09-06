# Cross-Platform Accessibility Matrix

Status per access feature and platform. Companion to
[HEAD_EYE_TRACKING.md](HEAD_EYE_TRACKING.md).

Legend: **Shipped** · **Partial** (works with gaps) · **Planned** (tracked) · **None**

| Feature | Android | iOS | Desktop |
| --- | --- | --- | --- |
| Dwell-to-select | Shipped (`InteractionInput`, configurable ms) | Shipped (shared `AccessInputController` via KoinBridge) | Partial (Screen buttons and communication controls; shared controller) |
| Tremor jitter filter (dwell re-arm delay) | Shipped (settings slider) | Partial (controller supports it; Swift does not sync the setting yet) | Partial (shared setting and controller wired) |
| Select key / switch press | Shipped (Space/Enter/F1–F12 bindings) | Shipped (bindings via settings sync) | Partial (select key acts on hovered communication target) |
| Rest mode toggle | Shipped (FAB is dwell/focus-reachable; rest key; **hold Select 2 s to resume**) | Partial (rest key + pause bridge; hold-to-resume needs bridge sync) | Partial (Rest/Resume button, rest key, hold Select 2 s; toggle is click/touch only) |
| Per-target selection debounce (#118) | Shipped (+ reject haptic) | Partial (shared logic available; not wired everywhere) | None |
| Selection highlight (#120) | Shipped | Partial | None |
| Hold-to-select duration | Shipped | Partial | None (setting stored, no runner support) |
| Auditory fishing (speak on hover) | Shipped | None | None |
| Haptic feedback | Shipped (confirm/reject/scan-tick events) | None (UIKit haptics not wired) | None |
| Switch scanning (#112–#114) | None ([#226](https://github.com/Jdreioe/Wingmate/issues/226)) | Shipped (native Swift scanning UI) | None |
| Auditory scanning prompts (#113) | None | None | None |
| Gaze input (native TD-I13) (#123–#129) | Planned | Planned | Partial (Linux fullscreen Screen runner, settings, diagnostics and bundled daemon startup; TD-I13 verification pending) |
| Head tracking providers (#125) | Planned | Planned | None (OS pointer only) |
| External gaze-provider boundary (#126) | Planned | Planned | None |
| Screen-reader operability of core speak flow | Partial — labels/i18n fixes tracked in #225; end-to-end audit: [#227](https://github.com/Jdreioe/Wingmate/issues/227) | Same as Android | None — `iced` draws its own widgets and exposes no platform accessibility tree |
| Undo for destructive phrase actions | Shipped (snackbar undo incl. sub-items) | None | None (no Phrase editing on desktop yet) |
| Print/PDF low-tech board fallback | Planned — [#228](https://github.com/Jdreioe/Wingmate/issues/228) | Planned | Planned |

## Desktop (recorded 2026-09)

The desktop client (`desktopApp/`, [#268](https://github.com/Jdreioe/Wingmate/issues/268))
now runs pointer dwell, the dwell re-arm delay, select-key activation, and Rest
mode through the shared `AccessInputController`. Settings > Access configures
these behaviors. They apply to Screen Buttons, Back, Clear, Hold, and Speak.
The target is highlighted and a progress bar shows dwell completion. Window
focus loss, pointer exit, and leaving communication cancel pending dwell.

Library, settings, and editor controls have no dwell targets yet. Rest/Resume
requires a click or touch, the rest shortcut, or holding Select for two seconds
and releasing to resume. Keyboard focus does not identify an access target yet;
the select shortcut acts on the hovered target. Hold-to-select remains stored
without runner support. Linux native gaze now connects the daemon stream to
actual widget bounds in the fullscreen Screen runner, including spanned Buttons.
It cancels invalid/stale input and reconnects automatically. Enabling is
session-only; daemon setup and calibration remain external. Windows
vendor-pointer and real TD-I13 verification remain outstanding. Desktop remains in development; see
[supported platforms](PLATFORM_SUPPORT.md) and ADR-0019.

## Deliberate non-goals (recorded 2026-08)

- **Attention-getter / call-bell button**: not a standard pattern in reference AAC setups
  (e.g., Tobii devices ship none). Revisit only if users ask.
- **Blind-user operation of the visual grid**: low priority by maintainer decision.
  Auditory fishing stays as-is; full auditory scanning is deferred with #113.

Keep this table honest: when a feature ships or regresses on a platform, update its row
in the same PR.
