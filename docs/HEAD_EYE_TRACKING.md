# Head and eye tracking

Wingmate supports two complementary tracking paths:

1. **OS pointer access** is available today. A mouse, head pointer, eye pointer,
   joystick, or adaptive switch can use the same pointer, dwell, and selection
   controls.
2. **Native gaze input** is planned first for Linux using
   [`tobiifreed`](https://github.com/Aetherall/tobiifree). Native input lets
   Wingmate reason about communication targets directly instead of moving a
   visible system cursor.

Native gaze is a Linux hardware capability, not a reason to duplicate AAC
behavior. Target activation, dwell timing, debounce, pause state, and feedback
continue to use Wingmate's shared access controller. Platform code only owns the
tracker connection, coordinate mapping, and native UI hit-testing.

## Current interaction settings

Open **Settings → Interaction** (inside **Accessibility** on clients that use
settings tabs) to configure:

- **Hover to select**: keep the pointer over a communication target to use it.
- **Select key**: press Space, Enter, or a chosen function key to use the target
  under the pointer or keyboard focus. Holding the key activates only once.
- **Rest mode key**: pause or resume Hover to select and the Select key.
- **Pointer emphasis**: keep the system pointer or emphasize the current target
  with a larger high-contrast ring or outline.

The Rest mode control remains at the edge of the workspace. While resting,
ordinary taps and clicks continue to work, and resuming starts a fresh dwell
timer.

## Native gaze architecture

```text
tobiifreed Unix socket
    → Linux gaze source (position, validity, timestamp)
    → native target hit-testing
    → target enter / exit
    → shared AccessInputController
    → dwell progress / activation / Rest mode
```

The initial provider connects to
`$XDG_RUNTIME_DIR/tobiifreed/gaze.sock`. It consumes the daemon's filtered,
normalized display coordinates and per-eye validity. It does not own the USB
device, copy `tobiifree`'s driver, use WebSockets, or inject a Wayland pointer.

The daemon protocol is experimental and currently sends a native Zig structure.
Wingmate must pin a known-compatible protocol layout, validate every frame's
length and required fields, reconnect safely, and fail closed when the stream is
unknown. Gaze samples stay in memory and their coordinates are never logged.

Direct display-to-widget mapping is supported across the fullscreen Linux app,
including navigation, communication and board buttons, editing actions,
checkboxes, lists, sliders, and text-field focus. A gaze dwell advances lists
and sliders by one value; text entry continues through the focused field's
on-screen or physical keyboard. Wayland does not reliably expose an ordinary
window's global position, so windowed native gaze requires a different
coordinate source or falls back to OS pointer access.

## Roadmap

| Phase | Issue | Outcome |
| --- | --- | --- |
| **P0 — shipped** | [#124](https://github.com/jdreioe/Wingmate/issues/124) | Pointer dwell, select key, Rest mode, pointer emphasis, and setup documentation |
| **P1 — implemented** | [#129](https://github.com/jdreioe/Wingmate/issues/129) | TD-I13/`tobiifreed` Linux vertical slice: connection, fullscreen whole-app hit-testing, existing dwell, and safe recovery |
| **P2** | [#158](https://github.com/jdreioe/Wingmate/issues/158) | Gaze-quality engine: hysteresis, target magnetism, gaze-loss behavior, calibration validation, and diagnostics |
| **P3** | [#126](https://github.com/jdreioe/Wingmate/issues/126) | Stable provider boundary and additional external gaze sources where real hardware justifies them |
| **P4** | [#125](https://github.com/jdreioe/Wingmate/issues/125) | Head tracking as a separate provider, including pointer and joystick modes |
| **P5** | [#127](https://github.com/jdreioe/Wingmate/issues/127) | Cross-platform discovery, documentation, feedback, and explicit mobile decisions |

Facial-expression selection remains independent work in
[#117](https://github.com/jdreioe/Wingmate/issues/117). A blink or expression is
a selection signal, not a gaze-position source.

## Product principles

- Select semantic communication targets, not pixels.
- Do not show a gaze-following cursor by default; emphasize the intended target.
- Use hysteresis so boundary jitter does not constantly reset dwell.
- Pause immediately when gaze is lost and require a fresh dwell after recovery.
- Keep Rest mode large, predictable, and available without precise aiming.
- Never activate editing or destructive controls through gaze unless explicitly
  enabled and protected.
- Do not log coordinates, eye measurements, phrase contents, or inferred usage
  patterns.
- Keep touch, mouse, switch, and OS accessibility input working when native gaze
  is disabled or unavailable.

## iPhone and iPad Eye Tracking

On a supported device with iOS or iPadOS 18 or later, open **Settings →
Accessibility → Eye Tracking**, enable Eye Tracking, and complete calibration.
The system pointer and dwell action work with Wingmate's normal buttons. Apple's
current instructions are at
<https://support.apple.com/guide/iphone/control-iphone-with-the-movement-of-your-eyes-iph66057d0f6/ios>.

Switch Control can also combine Eye Tracking or Head Tracking with a configured
Select Item switch. Wingmate leaves these native actions intact.

## Android and other Linux hardware

Android accepts external mouse, keyboard, switch, and OS-provided pointer events.
Linux accepts the same inputs through the desktop environment on both Wayland and
X11. Pointer emphasis adds to the system pointer; it never hides or replaces it.

Native Linux gaze initially targets the working TD-I13/`tobiifreed` setup. Other
hardware should be added through the provider boundary only after its actual
transport, licensing, calibration, and test device are known.
