# Cross-Platform Accessibility Matrix

Status per access feature and platform. Companion to
[HEAD_EYE_TRACKING.md](HEAD_EYE_TRACKING.md).

Legend: **Shipped** · **Partial** (works with gaps) · **Planned** (tracked) · **None**

| Feature | Android | iOS |
| --- | --- | --- |
| Dwell-to-select | Shipped (`InteractionInputRoot`, configurable ms) | Shipped (shared `AccessInputController`, driven by `IosViewModel`) |
| Tremor jitter filter (dwell re-arm delay) | Shipped (settings slider) | Shipped (settings slider synced to shared `AccessInputController`) |
| Select key / switch press | Shipped (Space/Enter/F1–F12 bindings) | Shipped (bindings via settings sync) |
| Rest mode toggle | Shipped (FAB is dwell/focus-reachable; rest key; **hold Select 2 s to resume**) | Partial (rest key + pause bridge; hold-to-resume needs bridge sync) |
| Per-target selection debounce (#118) | Shipped (+ reject haptic) | Shipped (shared `SelectionDebouncer` across Screens and Typing + confirm/reject haptics) |
| Selection highlight (#120) | Shipped (Screens, Typing Screen and compact Phrase row; inset black/white ring) | Shipped (Screens, Typing tray, Settings > Accessibility; updated ring/timer need device verification) |
| Select on touch-down (#110) | Shipped (Buttons, Typing action strip, Play/Stop; grids stop scrolling) | Shipped (Buttons, Typing tray & action strip, Message bar; grids stop scrolling) |
| Touch Exit / slide-to-correct ([#405](https://github.com/Jdreioe/Wingmate/issues/405)) | Shipped (grid-based Screens via shared `TouchExitController`; live highlight, replaceable auditory fishing preview, lift to activate, lift outside/cancel safely; Typing Phrase tray stays scrollable with tap and long-press editing) | Shipped (grid-based Screens via shared `TouchExitController`; live highlight, replaceable auditory fishing preview, lift to activate, lift outside/cancel safely; Typing Phrase tray stays scrollable with tap and context-menu editing) |
| Physical keyguard spacing ([#405](https://github.com/Jdreioe/Wingmate/issues/405)) | Shipped (grid-based Screen/Page cell gap & outer margin overrides with narrow-viewport clamping; persisted in OBZ and backups) | Shipped (grid-based Screen/Page cell gap & outer margin overrides with narrow-viewport clamping; persisted in OBZ and backups) |
| Swipe between Pages (#121) | Shipped (opt-in; Screen page order, Run mode; suppressed while Touch Exit or Select on touch-down owns the grid) | Shipped (opt-in; Screen page order and Typing tray, Run mode; suppressed while Touch Exit, Select on touch-down, or hold-to-select is active) |
| Hold-to-select duration | Shipped | Shipped (settings slider; Screens and Typing Phrases) |
| Auditory fishing (speak on hover / slide) | Shipped (pointer hover and Touch Exit; replaceable preview cues) | Shipped (pointer hover and Touch Exit; replaceable preview cues) |
| Haptic feedback | Shipped (confirm/reject/scan-tick events) | Shipped (UIKit confirm/reject/rest-mode tick via `AccessHaptics`) |
| Switch scanning (#112–#114) | None ([#226](https://github.com/Jdreioe/Wingmate/issues/226)) | Shipped (native Swift scanning UI) |
| Auditory scanning prompts (#113) | None | None |
| Gaze input (native TD-I13) (#123–#129) | Planned | Planned |
| Head tracking providers (#125) | Planned | Planned |
| External gaze-provider boundary (#126) | Planned | Planned |
| Screen-reader operability of core speak flow | Partial — labels/i18n fixes tracked in #225; end-to-end audit: [#228](https://github.com/Jdreioe/Wingmate/issues/228) | Same as Android |
| Undo for destructive phrase actions | Shipped (snackbar undo incl. sub-items) | Shipped (banner undo incl. sub-items and original order; recording files preserved for Message/History references) |
| Print/PDF low-tech board fallback ([#227](https://github.com/Jdreioe/Wingmate/issues/227)) | Shipped (Screen menu → Print (PDF): one sheet per Page, links name their page) | Shipped (Screen menu → Export Screen as PDF: one sheet per Page, links name their page) |

## Deliberate non-goals (recorded 2026-08)

- **Attention-getter / call-bell button**: not a standard pattern in reference AAC setups
  (e.g., Tobii devices ship none). Revisit only if users ask.
- **Blind-user operation of the visual grid**: low priority by maintainer decision.
  Auditory fishing stays as-is; full auditory scanning is deferred with #113.

Keep this table honest: when a feature ships or regresses on a platform, update its row
in the same PR.
