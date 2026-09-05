# Plan: native gaze on the TD-I13 through tobiifreed

Engineering plan for [#129](https://github.com/Jdreioe/Wingmate/issues/129)
(P1 of the access roadmap, [#123](https://github.com/Jdreioe/Wingmate/issues/123)).
User-facing setup lives in [HEAD_EYE_TRACKING.md](HEAD_EYE_TRACKING.md); the
per-platform status table is [ACCESSIBILITY_MATRIX.md](ACCESSIBILITY_MATRIX.md).

## Goal

A TD-I13 running Linux selects Wingmate communication targets with gaze, using
[`Aetherall/tobiifree`](https://github.com/Aetherall/tobiifree) for hardware
access and Wingmate's existing shared dwell/activation policy for selection.
No system pointer is moved and no gaze-following cursor is drawn.

Target client: `desktopApp/` (Rust + `iced`) on Linux. Android and iOS keep the
OS pointer path; native gaze there is out of scope (#127 records that decision).

## What tobiifree gives us

`tobiifreed` owns the USB device and broadcasts samples over a Unix socket, so
Wingmate never touches libusb and never links GPL-3.0 driver code — the process
boundary is also the licence boundary. Vendoring or reimplementing the driver is
a non-goal.

Pinned facts (read from `tobiifree` `main`, 2026-09):

| Item | Value |
| --- | --- |
| Socket | `$XDG_RUNTIME_DIR/tobiifreed/gaze.sock` (falls back to `/tmp`) |
| Framing | `[u8 msg_type][u32 LE payload_len][payload]`, 5-byte header |
| Subscribe | client msg `0x01`, payload `u32 LE 0x500` (`STREAM_GAZE`) |
| Disconnect | client msg `0xFF`, empty payload |
| Gaze event | server msg `0x01`, payload is one `GazeSample` |
| Other server msgs | `0x02` response, `0x03` display area, `0xFF` error (u32 code) |
| Clients | daemon serves up to 16 subscribers, so the overlay/demo can run alongside Wingmate |

`GazeSample` is a native Zig `extern struct` copied onto the wire, currently
**392 bytes**. `ARCHITECTURE.md` upstream still documents 232 bytes, so the
layout has changed at least once and the protocol is explicitly experimental.
Wingmate therefore pins these offsets and rejects any gaze frame whose payload
length is not exactly the pinned size:

| Offset | Type | Field | Use |
| --- | --- | --- | --- |
| 0 | u32 | `present_mask` | required-field check |
| 4 | u32 | `frame_counter` | drop duplicates/reorder |
| 8 | u32 | `validity_L` | `0` valid, `4` not detected |
| 12 | u32 | `validity_R` | same |
| 16 | i64 | `timestamp_us` | device µs clock, monotonic input to dwell |
| 40 | 2×f64 | `gaze_point_2d_norm` | filtered binocular gaze, `[0,1]²` |
| 376 | 2×f64 | `gaze_point_2d_unfiltered` | diagnostics only |

Present-mask bits used: `timestamp` `1<<0`, `frame_counter` `1<<1`,
`validity_L` `1<<2`, `validity_R` `1<<3`, `gaze_point_2d_norm` `1<<6`. Pupil
diameters, eye origins, 3D rays, and track-box positions are ignored: reading
them would tie us to more of a moving layout for no selection benefit.

Everything else — calibration, display area, per-eye geometry — stays with
`tobiifreed` and its own tools. Wingmate consumes `gaze_point_2d_norm` as the
device's already-calibrated answer to "where on the display area".

## Architecture

```text
tobiifreed ──unix socket──> gaze module (Rust)
                              │ validate frame, drop invalid samples
                              │ normalized [0,1]² -> window coordinates
                              │ hit-test the board grid
                              ▼
                            target enter / exit / lost
                              │ C API (wm_access_*)
                              ▼
                            AccessInputController (shared Kotlin)
                              │ dwell, re-arm delay, rest mode, debounce
                              ▼
                            Activate(target_id) -> existing Core::activate
```

Two rules keep this small:

- **Transport and geometry are platform code.** Sample decoding, coordinate
  mapping, and hit-testing live in Rust next to the `iced` widgets that own the
  layout. High-frequency coordinates never cross the C bridge.
- **Selection semantics stay shared.** Only semantic target transitions and
  clock ticks cross into `AccessInputController`, which already owns dwell,
  tremor re-arm, rest mode, and single activation and is unit-tested in
  `core/domain`. Desktop must not grow a second dwell implementation.

`AccessInputController` takes caller-supplied timestamps, so the bridge can pass
the sample clock and stay deterministic in tests.

## Milestones

Each milestone is independently reviewable and leaves the client working.

### M1 — Client transport

`desktopApp/rust/src/gaze/`: a `protocol` decoder that reassembles the daemon's
stream into samples, and a `client` that connects, subscribes, and reads them,
with bounded reconnect backoff for a daemon that is absent or restarts. A
status enum (`Disabled`, `Connecting`, `Connected`, `GazeLost`,
`IncompatibleProtocol`, `DaemonUnavailable`) names what the user is told.
Unknown message types are skipped by length; a gaze frame of unexpected length
or missing a required field stops decoding rather than guessing.

The `iced` subscription that drives this from the UI thread lands in M3
together with its consumer, so nothing runs in the background before something
uses it.

Tests: partial and coalesced reads, skipped message types, a gaze payload of
another size, `present_mask` missing a required field, an undetected eye,
absent and out-of-range coordinates, oversized payloads, and backoff growth
and reset.

### M2 — Shared controller on the C bridge

Add `wm_access_*` entry points (`target_entered`, `target_exited`,
`clear_transient_input`, `tick`, `set_paused`) that wrap the existing
`AccessInputController` and return the state plus any effect as JSON, matching
the JSON convention the rest of `DesktopCore` already uses. Sync `dwellMillis`
and the re-arm delay from shared `Settings`.

This also unblocks desktop dwell for mouse and OS-pointer users, which the
matrix currently records as a gap.

### M3 — Hit-testing and activation

Map `gaze_point_2d_norm` to the board grid and feed enter/exit into the bridge;
run `tick` on a frame timer; execute `Activate` through the existing activation
path so speak, insert, and navigate behave exactly as pointer hover does.
Fullscreen communication mode only — Wayland does not reliably report a normal
window's position on the display, so a windowed mapping would be guesswork.
Emphasis marks the resolved target; no cursor follows gaze.

Invalid samples (either eye `validity != 0`, or `gaze_point_2d_norm` absent or
outside `[0,1]²`) clear the current target after a short grace period, cancel
pending dwell, and require a fresh dwell on reacquisition.

Tests: synthetic samples over a fixture grid, boundary and span cells,
gaze-loss cancellation, rest mode suppressing activation.

### M4 — Settings, status, and docs

A gaze section in desktop Settings: enable/disable, daemon status, dwell
duration, and an opt-in diagnostics view. Disabled by default. Update
`HEAD_EYE_TRACKING.md` (setup and troubleshooting), the accessibility matrix,
`docs/PLATFORM_SUPPORT.md`, and `PRIVACY_POLICY.md`.

Interaction quality — hysteresis, magnetism, calibration validation — is
deliberately deferred to [#158](https://github.com/Jdreioe/Wingmate/issues/158);
M3 only has to be safe, not forgiving.

## Privacy

Gaze samples are sensitive operational data and stay in memory. Nothing logs
coordinates, pupil or eye measurements, validity histories, frame streams, or
the label of a gazed target. Diagnostics may show live values on screen; they
must not write them to disk or into error messages. Status logging is limited to
connection state and rejection reasons.

## Risks and open questions

- **Device support.** `tobiifree` documents the Tobii Eye Tracker 5
  (`2104:0313`). The TD-I13's integrated tracker must be confirmed to enumerate
  and stream through `tobiifreed` before M3; if it does not, M1/M2 still stand
  and the slice waits on hardware. This is why #129 is the vertical slice and
  #126 does not generalise a provider interface yet.
- **Protocol drift.** The layout already changed once. Failing closed on any
  unexpected length turns drift into a clear status message instead of garbage
  coordinates driving selections.
- **Coordinate space.** `gaze_point_2d_norm` is normalized to the daemon's
  configured display area. Multi-monitor and mismatched display-area
  configuration are not handled in this slice; the daemon owns that setup.
- **Timestamps.** `timestamp_us` is a device clock, not wall time. Use it only
  for elapsed-time arithmetic.

## Verification

Deterministic tests come first, then hardware:

1. Rust unit tests for framing, decoding, and hit-testing with synthetic samples.
2. Existing `core/domain` `AccessInputController` tests cover dwell and rest
   mode; add bridge-level tests only for the JSON boundary.
3. On the real TD-I13 with `tobiifreed`: select phrase and board targets,
   confirm gaze loss cannot complete an activation, confirm rest mode suppresses
   gaze, kill and restart the daemon while Wingmate runs, and confirm touch,
   mouse, keyboard, and switch input still work with gaze disabled.

Record the hardware result in #129 before closing it.
