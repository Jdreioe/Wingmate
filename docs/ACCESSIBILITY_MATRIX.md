# Cross-Platform Accessibility Matrix

Status per access feature and platform. Companion to
[HEAD_EYE_TRACKING.md](HEAD_EYE_TRACKING.md).

Legend: **Shipped** · **Partial** (works with gaps) · **Planned** (tracked) · **None**

| Feature | Android | iOS | Desktop |
| --- | --- | --- | --- |
| Dwell-to-select | Shipped (`InteractionInput`, configurable ms) | Shipped (shared `AccessInputController` via KoinBridge) | None (setting stored, no runner support) |
| Tremor jitter filter (dwell re-arm delay) | Shipped (settings slider) | Partial (controller supports it; Swift does not sync the setting yet) | None |
| Select key / switch press | Shipped (Space/Enter/F1–F12 bindings) | Shipped (bindings via settings sync) | None |
| Rest mode toggle | Shipped (FAB is dwell/focus-reachable; rest key; **hold Select 2 s to resume**) | Partial (rest key + pause bridge; hold-to-resume needs bridge sync) | None |
| Per-target selection debounce (#118) | Shipped (+ reject haptic) | Partial (shared logic available; not wired everywhere) | None |
| Selection highlight (#120) | Shipped | Partial | None |
| Hold-to-select duration | Shipped | Partial | None (setting stored, no runner support) |
| Auditory fishing (speak on hover) | Shipped | None | None |
| Haptic feedback | Shipped (confirm/reject/scan-tick events) | None (UIKit haptics not wired) | None |
| Switch scanning (#112–#114) | None ([#226](https://github.com/Jdreioe/Wingmate/issues/226)) | Shipped (native Swift scanning UI) | None |
| Auditory scanning prompts (#113) | None | None | None |
| Gaze input (native TD-I13) (#123–#129) | Planned | Planned | Planned — [#129](https://github.com/Jdreioe/Wingmate/issues/129), [plan](GAZE_TD_I13.md) |
| Head tracking providers (#125) | Planned | Planned | None (OS pointer only) |
| External gaze-provider boundary (#126) | Planned | Planned | None |
| Screen-reader operability of core speak flow | Partial — labels/i18n fixes tracked in #225; end-to-end audit: [#227](https://github.com/Jdreioe/Wingmate/issues/227) | Same as Android | None — `iced` draws its own widgets and exposes no platform accessibility tree |
| Undo for destructive phrase actions | Shipped (snackbar undo incl. sub-items) | None | None (no Phrase editing on desktop yet) |
| Print/PDF low-tech board fallback | Planned — [#228](https://github.com/Jdreioe/Wingmate/issues/228) | Planned | Planned |

## Desktop (recorded 2026-09)

The desktop client (`desktopApp/`, [#268](https://github.com/Jdreioe/Wingmate/issues/268))
accepts ordinary pointer and keyboard input and nothing else yet. Hold-to-select
and dwell-to-select are persisted in shared `Settings` so the Communicator's
other clients keep them, but desktop's runner does not act on them, so its
Settings screen deliberately offers no controls for them rather than sliders
that would do nothing. This is a recorded gap, not a parity exception: desktop
is not a supported client until it is closed. See
[supported platforms](PLATFORM_SUPPORT.md) and ADR-0019.

## Deliberate non-goals (recorded 2026-08)

- **Attention-getter / call-bell button**: not a standard pattern in reference AAC setups
  (e.g., Tobii devices ship none). Revisit only if users ask.
- **Blind-user operation of the visual grid**: low priority by maintainer decision.
  Auditory fishing stays as-is; full auditory scanning is deferred with #113.

Keep this table honest: when a feature ships or regresses on a platform, update its row
in the same PR.
