# Cross-Platform Accessibility Matrix

Status per access feature and platform. Companion to
[LINUX_ACCESSIBILITY_MATRIX.md](LINUX_ACCESSIBILITY_MATRIX.md) (Linux internals),
[HEAD_EYE_TRACKING.md](HEAD_EYE_TRACKING.md), and [PARITY_ROADMAP.md](PARITY_ROADMAP.md).

Legend: **Shipped** · **Partial** (works with gaps) · **Planned** (tracked) · **None**

| Feature | Android | iOS | Linux |
| --- | --- | --- | --- |
| Dwell-to-select | Shipped (`InteractionInput`, configurable ms) | Shipped (shared `AccessInputController` via KoinBridge) | Partial (bridge `tick` path; no re-arm filter yet) |
| Tremor jitter filter (dwell re-arm delay) | Shipped (settings slider) | Partial (controller supports it; Swift does not sync the setting yet) | None |
| Select key / switch press | Shipped (Space/Enter/F1–F12 bindings) | Shipped (bindings via settings sync) | Partial (key routing via bridge API) |
| Rest mode toggle | Shipped (FAB is dwell/focus-reachable; rest key; **hold Select 2 s to resume**) | Partial (rest key + pause bridge; hold-to-resume needs bridge sync) | Partial (rest key via bridge) |
| Per-target selection debounce (#118) | Shipped (+ reject haptic) | Partial (shared logic available; not wired everywhere) | None |
| Selection highlight (#120) | Shipped | Partial | None |
| Hold-to-select duration | Shipped | Partial | None |
| Auditory fishing (speak on hover) | Shipped | None | None |
| Haptic feedback | Shipped (confirm/reject/scan-tick events) | None (UIKit haptics not wired) | N/A |
| Switch scanning (#112–#114) | None ([#226](https://github.com/Jdreioe/Wingmate/issues/226)) | Shipped (native Swift scanning UI) | None |
| Auditory scanning prompts (#113) | None | None | None |
| Gaze input (native TD-I13) (#123–#129) | Planned | Planned | Planned (native branches exist) |
| Head tracking providers (#125) | Planned | Planned | Planned |
| External gaze-provider boundary (#126) | Planned | Planned | Planned |
| Screen-reader operability of core speak flow | Partial — labels/i18n fixes tracked in #225; end-to-end audit: [#227](https://github.com/Jdreioe/Wingmate/issues/227) | Same as Android | N/A (own UX audit doc) |
| Undo for destructive phrase actions | Shipped (snackbar undo incl. sub-items) | None | None |
| Print/PDF low-tech board fallback | Planned — [#228](https://github.com/Jdreioe/Wingmate/issues/228) | Planned | Planned |

## Deliberate non-goals (recorded 2026-08)

- **Attention-getter / call-bell button**: not a standard pattern in reference AAC setups
  (e.g., Tobii devices ship none). Revisit only if users ask.
- **Blind-user operation of the visual grid**: low priority by maintainer decision.
  Auditory fishing stays as-is; full auditory scanning is deferred with #113.

Keep this table honest: when a feature ships or regresses on a platform, update its row
in the same PR.
