# Linux accessibility and display matrix

This matrix is the manual parity checklist for the native Rust/Iced client. A setting
must not be exposed in Linux until its runtime behavior and activation path pass this
checklist.

| Capability | Linux status | Manual check |
|---|---|---|
| Desktop theme | Supported | Change the light/dark preference on COSMIC and a non-COSMIC desktop that implements the freedesktop appearance portal; confirm System follows both at startup and while running. Confirm explicit Light/Dark works when a desktop publishes a stale preference. |
| Desktop accessibility scaling | Platform-managed | Change COSMIC text/display scale, then check the 720×480 minimum window. |
| Primary navigation | Supported | Use Keyboard, Screens, and Settings from the COSMIC header with pointer, touch, and keyboard focus. Closing Settings returns to the prior workspace. |
| Symbolic control icons | Supported | Change the system icon theme on COSMIC and a non-COSMIC desktop; confirm playback, editing, navigation, import/export, and destructive controls remain visible and recognizable through portable symbolic-name fallbacks. |
| Touch targets and screen-reader names | Supported | Confirm primary icon controls are at least 48×48 logical pixels and expose a spoken name/tooltip. |
| Phrase-grid columns | Supported | Move the slider from 1 to 12 and confirm the phrase grid reflows immediately. |
| Speech-history visibility | Supported | Disable history, return to Communicate, and confirm History is absent. |
| Partner-window display settings | Supported when hardware is connected | Confirm the destination is hidden without FT232H hardware, appears after hot-plug, and disappears safely after unplugging. Change font/idle behavior and confirm the external display updates while connected. |
| Custom theme and per-control scaling | Deferred; hidden | No Linux control is shown. |
| Labels, symbol placement, and high contrast | Supported | Toggle labels and symbols, switch label-above/image-above ordering, and verify the COSMIC high-contrast palette follows the selected light/dark appearance. Buttons without a usable symbol must retain a visible text cue. |
| Board message bar | Supported | Verify screen/page overrides are honored. Activate Speak and Add, Add Only, and Speak Only fields; confirm shared localization/spelling rules, then test speak, backspace, clear, page navigation, and hiding the bar. |
| OBF spanning fields | Supported | Import boards with horizontal and vertical merged fields; confirm each repeated button ID renders once at the shared `fieldItems()` anchor and occupies its complete row/column span in Run and Edit modes. |
| OBF button actions | Supported | Verify append/space, backspace, clear, speak, home, and prediction fields. Multiple actions must execute in source order; unsupported actions must report their value without triggering ordinary speech. |
| Hold, dwell, highlight, debounce, and selection sound | Supported | Configure timing under Access, then verify pointer/touch activation fires once and the selected phrase or board field receives the visible highlight. |
| Auditory fishing | Supported | Enable exploration cues and verify entering a phrase, category, or board field produces a non-speech cue without activating it. |
| Switch scanning | Supported | Enable scanning, verify the highlight advances through enabled areas, and use Space or Enter to select the highlighted target exactly once. |
| Native gaze (TD-I13 via tobiifreed) | Supported (P1 slice; TD-I13 verification pending) | Start `tobiifreed`, enable Eye tracking under Access, and confirm the status line reaches Connected. Open eye-tracking communication from Typing and Screens; verify gaze reaches every phrase/category/control cell and every board-button/library cell, dwell speaks/inserts/navigates exactly like pointer hover, looking away cancels pending dwell, Rest mode suppresses activation and resume needs fresh dwell, stopping the daemon shows DaemonUnavailable without breaking touch/mouse/switch, and unknown protocol frames show Incompatible rather than activating. |
| Feature analytics and local usage logging | Unsupported; hidden | Onboarding and Privacy state that Linux does not report analytics. |

Before enabling a deferred access method, verify that pointer, keyboard, dwell, and
switch input converge on one activation command; cancellation cannot activate; and a
single physical action cannot trigger speech twice.
